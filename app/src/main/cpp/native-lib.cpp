#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <deque>
#include <array>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>

#include <aribcaption/aribcaption.h>
#include <aribcaption/b62_decoder.hpp>
#include <aribcaption/renderer.hpp>
#include <aribtlv/demuxer.hpp>
#include <aribtlv/duration_probe.hpp>
#include <aribtlv/recording.hpp>

// tsreadex コアヘッダ
#include "servicefilter.hpp"
#include "id3conv.hpp"
#include "util.hpp"
#include "traceb24.hpp"

namespace {

constexpr int ARIBCC_RENDER_FRAME_WIDTH = 1920;
constexpr int ARIBCC_RENDER_FRAME_HEIGHT = 1080;

struct AribCaptionDecoderContext {
    int captionType = 0;
    aribcc_context_t* context = nullptr;
    aribcc_decoder_t* decoder = nullptr;
    aribcc_renderer_t* renderer = nullptr;
    std::unique_ptr<aribcaption::B62Decoder> b62Decoder;
    std::unique_ptr<aribcaption::Renderer> b62Renderer;
    std::mutex mutex;
};

struct CaptionRegionGeometry {
    int x;
    int y;
    int width;
    int height;
};

constexpr size_t MAX_B62_BITMAP_BYTES_PER_CUE =
    static_cast<size_t>(ARIBCC_RENDER_FRAME_WIDTH) *
    static_cast<size_t>(ARIBCC_RENDER_FRAME_HEIGHT) * 4;

bool isB62RenderResultWithinBudget(const aribcaption::RenderResult& result) {
    size_t total = 0;
    for (const aribcaption::Image& image : result.images) {
        if (image.width <= 0 || image.height <= 0 ||
            image.width > ARIBCC_RENDER_FRAME_WIDTH ||
            image.height > ARIBCC_RENDER_FRAME_HEIGHT ||
            image.stride < image.width * 4) {
            return false;
        }
        if (image.bitmap.size() > MAX_B62_BITMAP_BYTES_PER_CUE - total) {
            return false;
        }
        total += image.bitmap.size();
    }
    return true;
}

std::vector<CaptionRegionGeometry> mapCaptionContentBoundsToRenderFrame(
    const aribcaption::Caption& caption
) {
    std::vector<CaptionRegionGeometry> regions;
    if (caption.plane_width <= 0 || caption.plane_height <= 0 ||
        caption.regions.empty()) {
        return regions;
    }

    const float magnification = std::min(
        static_cast<float>(ARIBCC_RENDER_FRAME_WIDTH) / caption.plane_width,
        static_cast<float>(ARIBCC_RENDER_FRAME_HEIGHT) / caption.plane_height);
    const int captionAreaWidth = static_cast<int>(caption.plane_width * magnification);
    const int captionAreaHeight = static_cast<int>(caption.plane_height * magnification);
    const int captionAreaStartX = (ARIBCC_RENDER_FRAME_WIDTH - captionAreaWidth) / 2;
    const int captionAreaStartY = (ARIBCC_RENDER_FRAME_HEIGHT - captionAreaHeight) / 2;
    const float scaleX = static_cast<float>(captionAreaWidth) / caption.plane_width;
    const float scaleY = static_cast<float>(captionAreaHeight) / caption.plane_height;

    regions.reserve(caption.regions.size());
    for (const aribcaption::CaptionRegion& region : caption.regions) {
        if (region.chars.empty()) continue;

        int contentLeft = std::numeric_limits<int>::max();
        int contentTop = std::numeric_limits<int>::max();
        int contentRight = std::numeric_limits<int>::min();
        int contentBottom = std::numeric_limits<int>::min();
        for (const aribcaption::CaptionChar& character : region.chars) {
            const int sectionWidth = character.section_width();
            const int sectionHeight = character.section_height();
            if (sectionWidth <= 0 || sectionHeight <= 0) continue;
            contentLeft = std::min(contentLeft, character.x);
            contentTop = std::min(contentTop, character.y);
            contentRight = std::max(contentRight, character.x + sectionWidth);
            contentBottom = std::max(contentBottom, character.y + sectionHeight);
        }
        if (contentRight <= contentLeft || contentBottom <= contentTop) continue;

        const int left = captionAreaStartX + static_cast<int>(contentLeft * scaleX);
        const int top = captionAreaStartY + static_cast<int>(contentTop * scaleY);
        const int right = captionAreaStartX +
            static_cast<int>(contentRight * scaleX);
        const int bottom = captionAreaStartY +
            static_cast<int>(contentBottom * scaleY);
        if (right <= left || bottom <= top) continue;
        regions.push_back({left, top, right - left, bottom - top});
    }
    return regions;
}

const char* b62ResourceMimeType(int dataType) {
    switch (dataType) {
    case 1: return "image/png";
    case 2: return "image/svg+xml";
    case 3: return "audio/aiff";
    case 4: return "audio/mpeg";
    case 5: return "audio/mp4";
    case 6: return "image/svg+xml";
    case 7: return "font/woff";
    default: return nullptr;
    }
}

jobject createAndroidBitmapFromRgba(
    JNIEnv* env,
    int width,
    int height,
    int sourceStride,
    const uint8_t* source,
    size_t sourceSize
) {
    if (width <= 0 || height <= 0 || source == nullptr || sourceStride < width * 4) {
        return nullptr;
    }

    const size_t rowBytes = static_cast<size_t>(width) * 4;
    const size_t requiredSize =
        static_cast<size_t>(height - 1) * static_cast<size_t>(sourceStride) + rowBytes;
    if (sourceSize < requiredSize) {
        return nullptr;
    }

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmapClass == nullptr || configClass == nullptr) {
        if (bitmapClass != nullptr) env->DeleteLocalRef(bitmapClass);
        if (configClass != nullptr) env->DeleteLocalRef(configClass);
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
        configClass,
        "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;");
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject config = argb8888Field == nullptr
        ? nullptr
        : env->GetStaticObjectField(configClass, argb8888Field);
    jobject bitmap = createBitmapMethod == nullptr || config == nullptr
        ? nullptr
        : env->CallStaticObjectMethod(
            bitmapClass,
            createBitmapMethod,
            static_cast<jint>(width),
            static_cast<jint>(height),
            config);

    if (config != nullptr) env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    if (env->ExceptionCheck()) {
        // Bitmap allocation can fail while the TV is under memory pressure. A subtitle frame
        // is expendable; do not let an OutOfMemoryError escape across JNI and kill playback.
        env->ExceptionClear();
        if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
        return nullptr;
    }
    if (bitmap == nullptr) {
        return nullptr;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != static_cast<uint32_t>(width) ||
        info.height != static_cast<uint32_t>(height) ||
        info.stride < rowBytes) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    auto* destination = static_cast<uint8_t*>(pixels);
    for (int row = 0; row < height; ++row) {
        std::memcpy(
            destination + static_cast<size_t>(row) * info.stride,
            source + static_cast<size_t>(row) * static_cast<size_t>(sourceStride),
            rowBytes);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

struct CaptionImageView {
    int dstX;
    int dstY;
    int width;
    int height;
    int stride;
    const uint8_t* bitmap;
    size_t bitmapSize;
};

template <typename ImageProvider>
jobject renderImagesToCue(
    JNIEnv* env,
    int64_t pts,
    int64_t duration,
    uint32_t imageCount,
    ImageProvider imageProvider,
    int64_t fallbackPtsMs,
    const std::vector<CaptionRegionGeometry>* regionGeometries = nullptr,
    int captionType = 0
) {
    pts = pts < 0 ? fallbackPtsMs : pts;

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "(I)V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject images = env->NewObject(arrayListClass, arrayListCtor, static_cast<jint>(imageCount));

    jclass imageClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionImage");
    jmethodID imageCtor = env->GetMethodID(
        imageClass,
        "<init>",
        "(IIIILandroid/graphics/Bitmap;Ljava/util/List;)V");
    jclass regionClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionRegion");
    jmethodID regionCtor = env->GetMethodID(regionClass, "<init>", "(IIII)V");

    for (uint32_t i = 0; i < imageCount; ++i) {
        const CaptionImageView image = imageProvider(i);
        jobject bitmap = createAndroidBitmapFromRgba(
            env,
            image.width,
            image.height,
            image.stride,
            image.bitmap,
            image.bitmapSize);
        if (bitmap == nullptr) continue;
        jobject regions = env->NewObject(
            arrayListClass,
            arrayListCtor,
            static_cast<jint>(regionGeometries == nullptr ? 0 : regionGeometries->size()));
        if (regionGeometries != nullptr) {
            for (const CaptionRegionGeometry& region : *regionGeometries) {
                jobject captionRegion = env->NewObject(
                    regionClass,
                    regionCtor,
                    static_cast<jint>(region.x),
                    static_cast<jint>(region.y),
                    static_cast<jint>(region.width),
                    static_cast<jint>(region.height));
                env->CallBooleanMethod(regions, arrayListAdd, captionRegion);
                env->DeleteLocalRef(captionRegion);
            }
        }
        jobject captionImage = env->NewObject(
            imageClass,
            imageCtor,
            static_cast<jint>(image.dstX),
            static_cast<jint>(image.dstY),
            static_cast<jint>(image.width),
            static_cast<jint>(image.height),
            bitmap,
            regions);
        env->CallBooleanMethod(images, arrayListAdd, captionImage);
        env->DeleteLocalRef(captionImage);
        env->DeleteLocalRef(regions);
        env->DeleteLocalRef(bitmap);
    }

    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    jmethodID cueCtor = env->GetMethodID(cueClass, "<init>", "(JJZIILjava/util/List;II)V");
    jobject cue = env->NewObject(
        cueClass,
        cueCtor,
        static_cast<jlong>(pts),
        static_cast<jlong>(duration),
        imageCount == 0 ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(ARIBCC_RENDER_FRAME_WIDTH),
        static_cast<jint>(ARIBCC_RENDER_FRAME_HEIGHT),
        images,
        0,
        static_cast<jint>(captionType));

    env->DeleteLocalRef(images);
    env->DeleteLocalRef(regionClass);
    env->DeleteLocalRef(imageClass);
    env->DeleteLocalRef(arrayListClass);
    env->DeleteLocalRef(cueClass);
    return cue;
}

jobject renderResultToCue(
    JNIEnv* env,
    aribcc_render_result_t& result,
    int64_t fallbackPtsMs,
    const std::vector<CaptionRegionGeometry>* regionGeometries = nullptr
) {
    const int64_t duration = result.duration == ARIBCC_DURATION_INDEFINITE
        ? -1
        : result.duration;
    return renderImagesToCue(
        env,
        result.pts,
        duration,
        result.image_count,
        [&result](uint32_t index) {
            const aribcc_image_t& image = result.images[index];
            return CaptionImageView{
                image.dst_x,
                image.dst_y,
                image.width,
                image.height,
                image.stride,
                image.bitmap,
                image.bitmap_size};
        },
        fallbackPtsMs,
        regionGeometries);
}

jobject renderB62ResultToCue(
    JNIEnv* env,
    aribcaption::RenderResult& result,
    int64_t fallbackPtsMs,
    const std::vector<CaptionRegionGeometry>* regionGeometries = nullptr,
    int captionType = 0
) {
    const int64_t duration = result.duration == aribcaption::DURATION_INDEFINITE
        ? -1
        : result.duration;
    return renderImagesToCue(
        env,
        result.pts,
        duration,
        static_cast<uint32_t>(result.images.size()),
        [&result](uint32_t index) {
            const aribcaption::Image& image = result.images[index];
            return CaptionImageView{
                image.dst_x,
                image.dst_y,
                image.width,
                image.height,
                image.stride,
                image.bitmap.data(),
                image.bitmap.size()};
        },
        fallbackPtsMs,
        regionGeometries,
        captionType);
}

} // namespace

class TsReadExContext {
public:
    int64_t seekOffset = 0;
    int limitReadBytesPerSec = 0;
    int timeoutSec = 0;
    int timeoutMode = 0;
    std::unordered_set<int> excludePidSet;

    CServiceFilter servicefilter;
    CTraceB24Caption traceb24;
    CID3Converter id3conv;

    int unitSize = 0;
    std::vector<uint8_t> residualBuffer;

    // 非同期キュー
    std::mutex mtx;
    std::deque<uint8_t> outputQueue;
    const size_t MAX_QUEUE_SIZE = 1024 * 1024 * 8; // 8MB

    TsReadExContext(int argc, char **argv) {
        for (int i = 0; i < argc; ++i) {
            std::string ss = argv[i];
            if (ss.length() < 2 || ss[0] != '-') continue;
            char c = ss[1];
            if (i < argc - 1) {
                if (c == 'z') { i++; }
                else if (c == 's') { seekOffset = std::atoll(argv[++i]); }
                else if (c == 'l') { limitReadBytesPerSec = std::atoi(argv[++i]) * 1024; }
                else if (c == 't') { timeoutSec = std::atoi(argv[++i]); }
                else if (c == 'm') { timeoutMode = std::atoi(argv[++i]); }
                else if (c == 'x') {
                    excludePidSet.clear();
                    char* pid_list = argv[++i];
                    char* token = std::strtok(pid_list, "/");
                    while (token != nullptr) {
                        excludePidSet.insert(std::atoi(token));
                        token = std::strtok(nullptr, "/");
                    }
                }
                else if (c == 'n') { servicefilter.SetProgramNumberOrIndex(std::atoi(argv[++i])); }
                else if (c == 'a') { servicefilter.SetAudio1Mode(std::atoi(argv[++i])); }
                else if (c == 'b') { servicefilter.SetAudio2Mode(std::atoi(argv[++i])); }
                else if (c == 'c') { servicefilter.SetCaptionMode(std::atoi(argv[++i])); }
                else if (c == 'u') { servicefilter.SetSuperimposeMode(std::atoi(argv[++i])); }
                else if (c == 'd') { id3conv.SetOption(std::atoi(argv[++i])); }
                else if (c == 'r') { i++; }
            }
        }
    }

    void pushData(const uint8_t* input, int inputLen) {
        std::vector<uint8_t> data;
        if (!residualBuffer.empty()) {
            data.insert(data.end(), residualBuffer.begin(), residualBuffer.end());
            residualBuffer.clear();
        }
        data.insert(data.end(), input, input + inputLen);

        const uint8_t* p = data.data();
        int size = (int)data.size();
        int pos = 0;

        if (unitSize == 0) {
            pos = resync_ts(p, size, &unitSize);
            if (unitSize == 0) {
                residualBuffer.insert(residualBuffer.end(), p, p + size);
                return;
            }
        }

        for (int i = pos; i + unitSize <= size; i += unitSize) {
            if (excludePidSet.find(extract_ts_header_pid(p + i)) == excludePidSet.end()) {
                servicefilter.AddPacket(p + i);
            }
        }

        int processedEnd = pos + ((size - pos) / unitSize) * unitSize;
        if (processedEnd < size) {
            residualBuffer.insert(residualBuffer.end(), p + processedEnd, p + size);
        }

        const auto& filtered = servicefilter.GetPackets();
        for (auto it = filtered.cbegin(); it != filtered.cend(); it += 188) {
            id3conv.AddPacket(&*it);
        }
        servicefilter.ClearPackets();

        const auto& finalOutput = id3conv.GetPackets();
        if (!finalOutput.empty()) {
            std::lock_guard<std::mutex> lock(mtx);
            if (outputQueue.size() + finalOutput.size() < MAX_QUEUE_SIZE) {
                outputQueue.insert(outputQueue.end(), finalOutput.begin(), finalOutput.end());
            }
            id3conv.ClearPackets();
        }
    }

    int popData(uint8_t* output, int maxOutputLen) {
        std::lock_guard<std::mutex> lock(mtx);
        if (outputQueue.empty()) return 0;

        int available = (int)outputQueue.size();
        int copySize = std::min(available, maxOutputLen);
        std::copy(outputQueue.begin(), outputQueue.begin() + copySize, output);
        outputQueue.erase(outputQueue.begin(), outputQueue.begin() + copySize);
        return copySize;
    }
};

class TlvDemuxContext final : public aribtlv::Sink {
public:
    TlvDemuxContext(JNIEnv* env, jobject callback, const int preferredVideoPacketId,
                    const bool buildRecordingIndex, const bool exposeAllVideoTracks)
        : callback_(env->NewGlobalRef(callback)), demuxer_(*this),
          preferredVideoPacketId_(preferredVideoPacketId),
          buildRecordingIndex_(buildRecordingIndex),
          exposeAllVideoTracks_(exposeAllVideoTracks) {
        if (buildRecordingIndex_) recordingIndex_.begin(false);
        jclass callbackClass = env->GetObjectClass(callback);
        onServiceMethod_ = env->GetMethodID(callbackClass, "onService", "(J[B)V");
        onTrackMethod_ = env->GetMethodID(
            callbackClass,
            "onTrack",
            "(JJIIILjava/lang/String;IJ[I[IIIZIII)V");
        onAccessUnitMethod_ = env->GetMethodID(
            callbackClass,
            "onAccessUnit",
            "(JI[BIJJJJJJJJ[I[I[[BZZ)V");
        onBroadcastClockMethod_ = env->GetMethodID(
            callbackClass,
            "onBroadcastClock",
            "(JJJJJZ)V");
        onEventInfoMethod_ = env->GetMethodID(
            callbackClass,
            "onEventInfo",
            "(JIZIIIIIJJIZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        onLayoutConfigurationMethod_ = env->GetMethodID(
            callbackClass,
            "onLayoutConfiguration",
            "(JI)V");
        onApplicationStateMethod_ = env->GetMethodID(
            callbackClass,
            "onApplicationState",
            "(JIIJIILjava/lang/String;[Ljava/lang/String;IJZ)V");
        onApplicationResourceMethod_ = env->GetMethodID(
            callbackClass,
            "onApplicationResource",
            "(JLjava/lang/String;Ljava/lang/String;[BI)V");
        onApplicationResourcesResetMethod_ = env->GetMethodID(
            callbackClass,
            "onApplicationResourcesReset",
            "()V");
        onErrorMethod_ = env->GetMethodID(
            callbackClass,
            "onError",
            "(IJZLjava/lang/String;)V");
        env->DeleteLocalRef(callbackClass);

        jclass byteArrayClass = env->FindClass("[B");
        byteArrayClass_ = static_cast<jclass>(env->NewGlobalRef(byteArrayClass));
        jintArray emptyIntArray = env->NewIntArray(0);
        emptyIntArray_ = static_cast<jintArray>(env->NewGlobalRef(emptyIntArray));
        jobjectArray emptyByteArrayArray = env->NewObjectArray(0, byteArrayClass, nullptr);
        emptyByteArrayArray_ = static_cast<jobjectArray>(env->NewGlobalRef(emptyByteArrayArray));
        env->DeleteLocalRef(emptyByteArrayArray);
        env->DeleteLocalRef(emptyIntArray);
        env->DeleteLocalRef(byteArrayClass);
    }

    ~TlvDemuxContext() override = default;

    void push(JNIEnv* env, const std::uint8_t* data, std::size_t size) {
        std::lock_guard<std::mutex> lock(mutex_);
        currentEnv_ = env;
        demuxer_.push(data, size);
        currentEnv_ = nullptr;
    }

    void flush(JNIEnv* env) {
        std::lock_guard<std::mutex> lock(mutex_);
        currentEnv_ = env;
        demuxer_.flush();
        if (buildRecordingIndex_) recordingIndex_.finalize();
        currentEnv_ = nullptr;
    }

    void reset(JNIEnv* env) {
        std::lock_guard<std::mutex> lock(mutex_);
        currentEnv_ = env;
        demuxer_.reset();
        if (buildRecordingIndex_) {
            recordingIndex_.begin(false);
        }
        selectedVideoTrackId_ = 0;
        audioTrackIds_.clear();
        playableAudioTrackIds_.clear();
        selectedCaptionTrackId_ = 0;
        selectedSuperimposeTrackId_ = 0;
        currentEnv_ = nullptr;
    }

    void reposition(const std::uint64_t inputOffset) {
        std::lock_guard<std::mutex> lock(mutex_);
        demuxer_.reposition(aribtlv::RepositionOptions{inputOffset, true});
    }

    std::array<std::int64_t, 4> seekPoints(const std::int64_t targetUs) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::array<std::int64_t, 4> result{-1, -1, -1, -1};
        if (!buildRecordingIndex_) return result;
        const auto points = recordingIndex_.seekPointsFor(
            aribtlv::Timestamp{targetUs, 1000000});
        if (!points.has_value()) return result;
        result[0] = points->first.presentation_time.value;
        result[1] = static_cast<std::int64_t>(points->first.signalling_offset);
        if (points->second.has_value()) {
            result[2] = points->second->presentation_time.value;
            result[3] = static_cast<std::int64_t>(points->second->signalling_offset);
        }
        return result;
    }

    void release(JNIEnv* env) {
        if (emptyByteArrayArray_ != nullptr) {
            env->DeleteGlobalRef(emptyByteArrayArray_);
            emptyByteArrayArray_ = nullptr;
        }
        if (emptyIntArray_ != nullptr) {
            env->DeleteGlobalRef(emptyIntArray_);
            emptyIntArray_ = nullptr;
        }
        if (byteArrayClass_ != nullptr) {
            env->DeleteGlobalRef(byteArrayClass_);
            byteArrayClass_ = nullptr;
        }
        if (reusableAccessUnitBuffer_ != nullptr) {
            env->DeleteGlobalRef(reusableAccessUnitBuffer_);
            reusableAccessUnitBuffer_ = nullptr;
            reusableAccessUnitCapacity_ = 0;
        }
        if (callback_ != nullptr) {
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
    }

    void onService(const aribtlv::ServiceInfo& info) override {
        if (!canCallback(onServiceMethod_)) return;
        jbyteArray packageId = makeByteArray(info.package_id);
        currentEnv_->CallVoidMethod(
            callback_,
            onServiceMethod_,
            static_cast<jlong>(info.context_id),
            packageId);
        currentEnv_->DeleteLocalRef(packageId);
    }

    void onTrack(const aribtlv::TrackInfo& info) override {
        if (info.kind == aribtlv::TrackKind::Video) {
            const bool matchesPreferred = preferredVideoPacketId_ < 0 ||
                info.packet_id == preferredVideoPacketId_;
            if (selectedVideoTrackId_ == 0 && matchesPreferred) {
                selectedVideoTrackId_ = info.track_id;
                if (buildRecordingIndex_) recordingIndex_.selectVideoTrack(info.track_id);
                if (!exposeAllVideoTracks_) {
                    demuxer_.selectTrack(aribtlv::TrackKind::Video, info.track_id);
                }
            }
            if (!exposeAllVideoTracks_ &&
                (selectedVideoTrackId_ == 0 || info.track_id != selectedVideoTrackId_)) return;
        }
        if (info.kind == aribtlv::TrackKind::Audio) {
            audioTrackIds_.insert(info.track_id);
            const bool isUnsupported22_2 = info.audio.has_value() &&
                info.audio->channel_layout == aribtlv::AudioChannelLayout::Channels22_2;
            if (isUnsupported22_2) return;

            // Keep audio unselected in aribtlv so every playable MMT audio asset is
            // emitted. Media3 needs all of them as separate TrackGroups in order to
            // switch audio without recreating the raw-MMTS demuxer.
            playableAudioTrackIds_.insert(info.track_id);
        }
        if (info.kind == aribtlv::TrackKind::Subtitle) {
            if (!info.subtitle.has_value() || info.subtitle->type > 1) return;
            std::uint64_t& selectedTrackId = info.subtitle->type == 0
                ? selectedCaptionTrackId_
                : selectedSuperimposeTrackId_;
            if (selectedTrackId == 0) {
                selectedTrackId = info.track_id;
            }
            if (info.track_id != selectedTrackId) return;
        }
        if (!canCallback(onTrackMethod_)) return;
        jstring language = currentEnv_->NewStringUTF(info.language.c_str());
        const auto audioLayout = info.audio.has_value()
            ? static_cast<jint>(info.audio->channel_layout)
            : static_cast<jint>(aribtlv::AudioChannelLayout::Unknown);
        const auto assetGroupCount = static_cast<jsize>(info.asset_groups.size());
        jintArray assetGroupIdentifications = currentEnv_->NewIntArray(assetGroupCount);
        jintArray assetGroupSelectionLevels = currentEnv_->NewIntArray(assetGroupCount);
        if (assetGroupCount > 0) {
            std::vector<jint> identifications(static_cast<std::size_t>(assetGroupCount));
            std::vector<jint> selectionLevels(static_cast<std::size_t>(assetGroupCount));
            for (jsize index = 0; index < assetGroupCount; ++index) {
                const auto& group = info.asset_groups[static_cast<std::size_t>(index)];
                identifications[static_cast<std::size_t>(index)] =
                    static_cast<jint>(group.group_identification);
                selectionLevels[static_cast<std::size_t>(index)] =
                    static_cast<jint>(group.selection_level);
            }
            currentEnv_->SetIntArrayRegion(
                assetGroupIdentifications, 0, assetGroupCount, identifications.data());
            currentEnv_->SetIntArrayRegion(
                assetGroupSelectionLevels, 0, assetGroupCount, selectionLevels.data());
        }
        const auto audioSampleRate = info.audio.has_value()
            ? static_cast<jint>(info.audio->sample_rate)
            : 0;
        const auto audioMainComponent = info.audio.has_value() && info.audio->main_component;
        const auto subtitleOperationMode = info.subtitle.has_value()
            ? static_cast<jint>(info.subtitle->operation_mode)
            : -1;
        const auto subtitleType = info.subtitle.has_value()
            ? static_cast<jint>(info.subtitle->type)
            : -1;
        const auto subtitleTimingMode = info.subtitle.has_value()
            ? static_cast<jint>(info.subtitle->timing_mode)
            : -1;
        currentEnv_->CallVoidMethod(
            callback_,
            onTrackMethod_,
            static_cast<jlong>(info.track_id),
            static_cast<jlong>(info.context_id),
            static_cast<jint>(info.packet_id),
            static_cast<jint>(info.kind),
            static_cast<jint>(info.codec),
            language,
            static_cast<jint>(info.component_tag),
            static_cast<jlong>(info.timescale),
            assetGroupIdentifications,
            assetGroupSelectionLevels,
            audioLayout,
            audioSampleRate,
            audioMainComponent ? JNI_TRUE : JNI_FALSE,
            subtitleType,
            subtitleOperationMode,
            subtitleTimingMode);
        currentEnv_->DeleteLocalRef(language);
        currentEnv_->DeleteLocalRef(assetGroupIdentifications);
        currentEnv_->DeleteLocalRef(assetGroupSelectionLevels);
    }

    void onAccessUnit(aribtlv::AccessUnit&& unit) override {
        if (buildRecordingIndex_) recordingIndex_.observe(unit);
        if (audioTrackIds_.find(unit.track_id) != audioTrackIds_.end() &&
            playableAudioTrackIds_.find(unit.track_id) == playableAudioTrackIds_.end()) {
            return;
        }
        if (unit.codec == aribtlv::Codec::Ttml &&
            unit.track_id != selectedCaptionTrackId_ &&
            unit.track_id != selectedSuperimposeTrackId_) {
            return;
        }
        if (!canCallback(onAccessUnitMethod_)) return;
        const bool mayReuse =
            unit.codec != aribtlv::Codec::Ttml &&
            unit.data.size() <= MAX_RETAINED_ACCESS_UNIT_BYTES;
        jbyteArray data = mayReuse ? makeReusableAccessUnitByteArray(unit.data) : nullptr;
        const bool isReusable = data != nullptr;
        if (data == nullptr) data = makeByteArray(unit.data);
        const jsize resourceCount = static_cast<jsize>(unit.subtitle_resources.size());
        const bool hasResources = resourceCount > 0;
        jintArray resourceIndices = hasResources
            ? currentEnv_->NewIntArray(resourceCount)
            : emptyIntArray_;
        jintArray resourceTypes = hasResources
            ? currentEnv_->NewIntArray(resourceCount)
            : emptyIntArray_;
        jobjectArray resourceData = hasResources
            ? currentEnv_->NewObjectArray(resourceCount, byteArrayClass_, nullptr)
            : emptyByteArrayArray_;
        if (resourceCount > 0) {
            std::vector<jint> indices(static_cast<size_t>(resourceCount));
            std::vector<jint> types(static_cast<size_t>(resourceCount));
            for (jsize index = 0; index < resourceCount; ++index) {
                const auto& resource = unit.subtitle_resources[static_cast<size_t>(index)];
                indices[static_cast<size_t>(index)] = resource.subsample_number;
                types[static_cast<size_t>(index)] = resource.data_type;
                jbyteArray resourceBytes = makeByteArray(resource.data);
                currentEnv_->SetObjectArrayElement(resourceData, index, resourceBytes);
                currentEnv_->DeleteLocalRef(resourceBytes);
            }
            currentEnv_->SetIntArrayRegion(resourceIndices, 0, resourceCount, indices.data());
            currentEnv_->SetIntArrayRegion(resourceTypes, 0, resourceCount, types.data());
        }
        currentEnv_->CallVoidMethod(
            callback_,
            onAccessUnitMethod_,
            static_cast<jlong>(unit.track_id),
            static_cast<jint>(unit.codec),
            data,
            static_cast<jint>(unit.data.size()),
            static_cast<jlong>(unit.pts.value),
            static_cast<jlong>(unit.pts.timescale),
            static_cast<jlong>(unit.dts.value),
            static_cast<jlong>(unit.dts.timescale),
            static_cast<jlong>(unit.input_offset),
            unit.mpu_sequence_number
                ? static_cast<jlong>(*unit.mpu_sequence_number)
                : static_cast<jlong>(-1),
            static_cast<jlong>(unit.subtitle_reference_start_pts
                ? unit.subtitle_reference_start_pts->value
                : 0),
            static_cast<jlong>(unit.subtitle_reference_start_pts
                ? unit.subtitle_reference_start_pts->timescale
                : 0),
            resourceIndices,
            resourceTypes,
            resourceData,
            unit.random_access ? JNI_TRUE : JNI_FALSE,
            unit.discontinuity ? JNI_TRUE : JNI_FALSE);
        if (hasResources) {
            currentEnv_->DeleteLocalRef(resourceData);
            currentEnv_->DeleteLocalRef(resourceTypes);
            currentEnv_->DeleteLocalRef(resourceIndices);
        }
        if (!isReusable) currentEnv_->DeleteLocalRef(data);
    }

    void onBroadcastClock(const aribtlv::BroadcastClock& clock) override {
        if (!canCallback(onBroadcastClockMethod_)) return;
        currentEnv_->CallVoidMethod(
            callback_,
            onBroadcastClockMethod_,
            static_cast<jlong>(clock.media_time.value),
            static_cast<jlong>(clock.media_time.timescale),
            static_cast<jlong>(clock.broadcast_time.value),
            static_cast<jlong>(clock.broadcast_time.timescale),
            static_cast<jlong>(clock.input_offset),
            clock.discontinuity ? JNI_TRUE : JNI_FALSE);
    }

    void onEventInfo(const aribtlv::EventInfo& event) override {
        if (!canCallback(onEventInfoMethod_)) return;
        jstring language = currentEnv_->NewStringUTF(event.language.c_str());
        jstring title = currentEnv_->NewStringUTF(event.title.c_str());
        jstring description = currentEnv_->NewStringUTF(event.description.c_str());
        currentEnv_->CallVoidMethod(
            callback_,
            onEventInfoMethod_,
            static_cast<jlong>(event.context_id),
            static_cast<jint>(event.table_id),
            event.current_next ? JNI_TRUE : JNI_FALSE,
            static_cast<jint>(event.section_number),
            static_cast<jint>(event.service_id),
            static_cast<jint>(event.tlv_stream_id),
            static_cast<jint>(event.original_network_id),
            static_cast<jint>(event.event_id),
            event.start_time_unix_milliseconds
                ? static_cast<jlong>(*event.start_time_unix_milliseconds)
                : static_cast<jlong>(-1),
            event.duration_seconds
                ? static_cast<jlong>(*event.duration_seconds)
                : static_cast<jlong>(-1),
            static_cast<jint>(event.running_status),
            event.free_ca_mode ? JNI_TRUE : JNI_FALSE,
            language,
            title,
            description);
        currentEnv_->DeleteLocalRef(language);
        currentEnv_->DeleteLocalRef(title);
        currentEnv_->DeleteLocalRef(description);
    }

    void onLayoutConfiguration(const aribtlv::LayoutConfiguration& layout) override {
        if (!canCallback(onLayoutConfigurationMethod_)) return;
        currentEnv_->CallVoidMethod(
            callback_,
            onLayoutConfigurationMethod_,
            static_cast<jlong>(layout.context_id),
            layout.background_color_rgb
                ? static_cast<jint>(*layout.background_color_rgb)
                : static_cast<jint>(-1));
    }

    void onApplicationState(const aribtlv::ApplicationState& state) override {
        if (!canCallback(onApplicationStateMethod_)) return;
        jstring entryPath = currentEnv_->NewStringUTF(state.application.entry_path.c_str());
        jclass stringClass = currentEnv_->FindClass("java/lang/String");
        jobjectArray transportUrls = currentEnv_->NewObjectArray(
            static_cast<jsize>(state.application.transport_urls.size()),
            stringClass,
            nullptr);
        for (std::size_t index = 0; index < state.application.transport_urls.size(); ++index) {
            jstring transportUrl = currentEnv_->NewStringUTF(
                state.application.transport_urls[index].c_str());
            currentEnv_->SetObjectArrayElement(
                transportUrls,
                static_cast<jsize>(index),
                transportUrl);
            currentEnv_->DeleteLocalRef(transportUrl);
        }
        currentEnv_->CallVoidMethod(
            callback_,
            onApplicationStateMethod_,
            static_cast<jlong>(state.application.context_id),
            static_cast<jint>(state.application.application_type),
            static_cast<jint>(state.application.organization_id),
            static_cast<jlong>(state.application.application_id),
            static_cast<jint>(state.application.control_code),
            static_cast<jint>(state.application.application_priority),
            entryPath,
            transportUrls,
            static_cast<jint>(state.state),
            static_cast<jlong>(state.resource_count),
            state.entry_ready ? JNI_TRUE : JNI_FALSE);
        currentEnv_->DeleteLocalRef(entryPath);
        currentEnv_->DeleteLocalRef(transportUrls);
        currentEnv_->DeleteLocalRef(stringClass);
    }

    void onApplicationResource(aribtlv::ApplicationResource&& resource) override {
        if (!canCallback(onApplicationResourceMethod_)) return;
        jstring path = currentEnv_->NewStringUTF(resource.path.c_str());
        jstring contentType = currentEnv_->NewStringUTF(resource.content_type.c_str());
        jbyteArray data = makeByteArray(resource.data);
        currentEnv_->CallVoidMethod(
            callback_,
            onApplicationResourceMethod_,
            static_cast<jlong>(resource.context_id),
            path,
            contentType,
            data,
            static_cast<jint>(resource.version));
        currentEnv_->DeleteLocalRef(path);
        currentEnv_->DeleteLocalRef(contentType);
        currentEnv_->DeleteLocalRef(data);
    }

    void onApplicationResourcesReset() override {
        if (!canCallback(onApplicationResourcesResetMethod_)) return;
        currentEnv_->CallVoidMethod(callback_, onApplicationResourcesResetMethod_);
    }

    void onError(const aribtlv::Error& error) override {
        if (!canCallback(onErrorMethod_)) return;
        jstring message = currentEnv_->NewStringUTF(error.message.c_str());
        currentEnv_->CallVoidMethod(
            callback_,
            onErrorMethod_,
            static_cast<jint>(error.code),
            static_cast<jlong>(error.input_offset),
            error.recoverable ? JNI_TRUE : JNI_FALSE,
            message);
        currentEnv_->DeleteLocalRef(message);
    }

private:
    bool canCallback(jmethodID method) const {
        return currentEnv_ != nullptr && callback_ != nullptr && method != nullptr &&
               !currentEnv_->ExceptionCheck();
    }

    jbyteArray makeByteArray(const std::vector<std::uint8_t>& source) const {
        jbyteArray result = currentEnv_->NewByteArray(static_cast<jsize>(source.size()));
        if (result != nullptr && !source.empty()) {
            currentEnv_->SetByteArrayRegion(
                result,
                0,
                static_cast<jsize>(source.size()),
                reinterpret_cast<const jbyte*>(source.data()));
        }
        return result;
    }

    jbyteArray makeReusableAccessUnitByteArray(const std::vector<std::uint8_t>& source) {
        if (source.size() > MAX_RETAINED_ACCESS_UNIT_BYTES) return nullptr;
        if (reusableAccessUnitCapacity_ < static_cast<jsize>(source.size())) {
            std::size_t targetCapacity = MIN_RETAINED_ACCESS_UNIT_BYTES;
            while (targetCapacity < source.size()) targetCapacity *= 2;
            targetCapacity = std::min(targetCapacity, MAX_RETAINED_ACCESS_UNIT_BYTES);

            jbyteArray localBuffer = currentEnv_->NewByteArray(static_cast<jsize>(targetCapacity));
            if (localBuffer == nullptr) return nullptr;
            auto* globalBuffer = static_cast<jbyteArray>(currentEnv_->NewGlobalRef(localBuffer));
            currentEnv_->DeleteLocalRef(localBuffer);
            if (globalBuffer == nullptr) return nullptr;

            if (reusableAccessUnitBuffer_ != nullptr) {
                currentEnv_->DeleteGlobalRef(reusableAccessUnitBuffer_);
            }
            reusableAccessUnitBuffer_ = globalBuffer;
            reusableAccessUnitCapacity_ = static_cast<jsize>(targetCapacity);
        }
        if (!source.empty()) {
            currentEnv_->SetByteArrayRegion(
                reusableAccessUnitBuffer_,
                0,
                static_cast<jsize>(source.size()),
                reinterpret_cast<const jbyte*>(source.data()));
        }
        return reusableAccessUnitBuffer_;
    }

    static constexpr std::size_t MIN_RETAINED_ACCESS_UNIT_BYTES = 64 * 1024;
    static constexpr std::size_t MAX_RETAINED_ACCESS_UNIT_BYTES = 4 * 1024 * 1024;
    JNIEnv* currentEnv_ = nullptr;
    std::mutex mutex_;
    jobject callback_ = nullptr;
    jclass byteArrayClass_ = nullptr;
    jintArray emptyIntArray_ = nullptr;
    jobjectArray emptyByteArrayArray_ = nullptr;
    jbyteArray reusableAccessUnitBuffer_ = nullptr;
    jsize reusableAccessUnitCapacity_ = 0;
    jmethodID onServiceMethod_ = nullptr;
    jmethodID onTrackMethod_ = nullptr;
    jmethodID onAccessUnitMethod_ = nullptr;
    jmethodID onBroadcastClockMethod_ = nullptr;
    jmethodID onEventInfoMethod_ = nullptr;
    jmethodID onLayoutConfigurationMethod_ = nullptr;
    jmethodID onApplicationStateMethod_ = nullptr;
    jmethodID onApplicationResourceMethod_ = nullptr;
    jmethodID onApplicationResourcesResetMethod_ = nullptr;
    jmethodID onErrorMethod_ = nullptr;
    aribtlv::Demuxer demuxer_;
    aribtlv::RecordingIndex recordingIndex_;
    int preferredVideoPacketId_ = -1;
    bool buildRecordingIndex_ = false;
    bool exposeAllVideoTracks_ = false;
    std::uint64_t selectedVideoTrackId_ = 0;
    std::unordered_set<std::uint64_t> audioTrackIds_;
    std::unordered_set<std::uint64_t> playableAudioTrackIds_;
    std::uint64_t selectedCaptionTrackId_ = 0;
    std::uint64_t selectedSuperimposeTrackId_ = 0;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_beeregg2001_komorebi_NativeLib_openFilter(JNIEnv *env, jobject thiz, jobjectArray args) {
    int argc = env->GetArrayLength(args);
    std::vector<std::string> arg_strings;
    std::vector<char*> argv_ptrs;
    for (int i = 0; i < argc; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(args, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        arg_strings.push_back(s);
        env->ReleaseStringUTFChars(js, s);
    }
    for (auto& s : arg_strings) { argv_ptrs.push_back(const_cast<char*>(s.c_str())); }
    return reinterpret_cast<jlong>(new TsReadExContext((int)argv_ptrs.size(), argv_ptrs.data()));
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_pushDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject inputBuf, jint inputLen) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return;
    uint8_t* inPtr = (uint8_t*)env->GetDirectBufferAddress(inputBuf);
    if (inPtr) ctx->pushData(inPtr, inputLen);
}

JNIEXPORT jint JNICALL
Java_com_beeregg2001_komorebi_NativeLib_popDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject outputBuf, jint maxLen) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return -1;
    uint8_t* outPtr = (uint8_t*)env->GetDirectBufferAddress(outputBuf);
    if (!outPtr) return -1;
    return ctx->popData(outPtr, maxLen);
}

JNIEXPORT jint JNICALL
Java_com_beeregg2001_komorebi_NativeLib_processDataBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject inputBuf, jint inputLen, jobject outputBuf) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (!ctx) return -1;
    uint8_t* inPtr = (uint8_t*)env->GetDirectBufferAddress(inputBuf);
    uint8_t* outPtr = (uint8_t*)env->GetDirectBufferAddress(outputBuf);
    jlong outCap = env->GetDirectBufferCapacity(outputBuf);
    if (inPtr) ctx->pushData(inPtr, inputLen);
    if (outPtr) return ctx->popData(outPtr, (int)outCap);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeFilter(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<TsReadExContext*>(handle);
    if (ctx) delete ctx;
}

JNIEXPORT jlong JNICALL
Java_com_beeregg2001_komorebi_NativeLib_openCaptionDecoder(
    JNIEnv *env,
    jobject thiz,
    jint captionType
) {
    if (captionType != 0 && captionType != 1) return 0;
    const auto nativeCaptionType = captionType == 0
        ? ARIBCC_CAPTIONTYPE_CAPTION
        : ARIBCC_CAPTIONTYPE_SUPERIMPOSE;
    const auto cppCaptionType = captionType == 0
        ? aribcaption::CaptionType::kCaption
        : aribcaption::CaptionType::kSuperimpose;
    auto* ctx = new AribCaptionDecoderContext();
    ctx->captionType = captionType;
    ctx->context = aribcc_context_alloc();
    if (!ctx->context) {
        delete ctx;
        return 0;
    }
    ctx->decoder = aribcc_decoder_alloc(ctx->context);
    if (!ctx->decoder) {
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    if (!aribcc_decoder_initialize(
            ctx->decoder,
            ARIBCC_ENCODING_SCHEME_ARIB_STD_B24_JIS,
            nativeCaptionType,
            ARIBCC_PROFILE_A,
            ARIBCC_LANGUAGEID_FIRST)) {
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    ctx->renderer = aribcc_renderer_alloc(ctx->context);
    if (!ctx->renderer) {
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    if (!aribcc_renderer_initialize(
            ctx->renderer,
            nativeCaptionType,
            ARIBCC_FONTPROVIDER_TYPE_AUTO,
            ARIBCC_TEXTRENDERER_TYPE_AUTO)) {
        aribcc_renderer_free(ctx->renderer);
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    aribcc_renderer_set_frame_size(ctx->renderer, ARIBCC_RENDER_FRAME_WIDTH, ARIBCC_RENDER_FRAME_HEIGHT);
    aribcc_renderer_set_margins(ctx->renderer, 0, 0, 0, 0);
    aribcc_renderer_set_storage_policy(ctx->renderer, ARIBCC_CAPTION_STORAGE_POLICY_MINIMUM, 0);
    aribcc_renderer_set_force_stroke_text(ctx->renderer, true);
    aribcc_renderer_set_replace_drcs(ctx->renderer, true);
    aribcc_renderer_set_merge_region_images(ctx->renderer, false);

    try {
        auto* cppContext = reinterpret_cast<aribcaption::Context*>(ctx->context);
        ctx->b62Decoder = std::make_unique<aribcaption::B62Decoder>(
            *cppContext,
            cppCaptionType);
        ctx->b62Renderer = std::make_unique<aribcaption::Renderer>(*cppContext);
        if (!ctx->b62Renderer->Initialize(
                cppCaptionType,
                aribcaption::FontProviderType::kAuto,
                aribcaption::TextRendererType::kAuto)) {
            throw std::runtime_error("Failed to initialize B62 renderer");
        }
    } catch (...) {
        ctx->b62Renderer.reset();
        ctx->b62Decoder.reset();
        aribcc_renderer_free(ctx->renderer);
        aribcc_decoder_free(ctx->decoder);
        aribcc_context_free(ctx->context);
        delete ctx;
        return 0;
    }
    ctx->b62Renderer->SetFrameSize(ARIBCC_RENDER_FRAME_WIDTH, ARIBCC_RENDER_FRAME_HEIGHT);
    ctx->b62Renderer->SetMargins(0, 0, 0, 0);
    ctx->b62Renderer->SetStoragePolicy(aribcaption::CaptionStoragePolicy::kMinimum);
    ctx->b62Renderer->SetForceStrokeText(true);
    ctx->b62Renderer->SetReplaceDRCS(true);
    ctx->b62Renderer->SetMergeRegionImages(true);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jobject JNICALL
Java_com_beeregg2001_komorebi_NativeLib_decodeCaption(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jlong ptsMs) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder || !ctx->renderer || !data) return nullptr;

    jsize length = env->GetArrayLength(data);
    if (length <= 0) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return nullptr;

    aribcc_caption_t caption = {};
    int status;
    aribcc_render_result_t renderResult = {};
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        status = aribcc_decoder_decode(
            ctx->decoder,
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length),
            static_cast<int64_t>(ptsMs),
            &caption);
        if (status == ARIBCC_DECODE_STATUS_GOT_CAPTION) {
            aribcc_renderer_append_caption(ctx->renderer, &caption);
            aribcc_render_status_t renderStatus = aribcc_renderer_render(
                ctx->renderer,
                static_cast<int64_t>(ptsMs),
                &renderResult);
            if (renderStatus != ARIBCC_RENDER_STATUS_GOT_IMAGE &&
                renderStatus != ARIBCC_RENDER_STATUS_GOT_IMAGE_UNCHANGED) {
                const bool isClearOnlyCaption =
                    renderStatus == ARIBCC_RENDER_STATUS_NO_IMAGE &&
                    (caption.flags & ARIBCC_CAPTIONFLAGS_CLEARSCREEN) != 0 &&
                    caption.region_count == 0;
                if (!isClearOnlyCaption) {
                    aribcc_caption_cleanup(&caption);
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return nullptr;
                }

                // A CS-only caption intentionally renders no bitmap. Preserve it as an empty
                // cue so the Kotlin timeline can clear the previous indefinite caption.
                renderResult.pts = caption.pts;
                renderResult.duration = caption.wait_duration;
                renderResult.images = nullptr;
                renderResult.image_count = 0;
            }
        }
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (status != ARIBCC_DECODE_STATUS_GOT_CAPTION) {
        return nullptr;
    }

    jobject cue = renderResultToCue(env, renderResult, ptsMs);
    aribcc_render_result_cleanup(&renderResult);
    aribcc_caption_cleanup(&caption);
    return cue;
}

JNIEXPORT jobjectArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_decodeB62Captions(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray data,
    jlong ptsMs,
    jint operationMode,
    jint timingMode,
    jlong referenceStartPtsMs,
    jlong resourceScopeId,
    jintArray resourceIndices,
    jintArray resourceTypes,
    jobjectArray resourceData,
    jboolean discontinuity) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    if (!ctx || !ctx->b62Decoder || !ctx->b62Renderer || !data ||
        !resourceIndices || !resourceTypes || !resourceData) {
        return env->NewObjectArray(0, cueClass, nullptr);
    }

    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return env->NewObjectArray(0, cueClass, nullptr);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return env->NewObjectArray(0, cueClass, nullptr);

    std::vector<jobject> cues;
    std::vector<jbyteArray> pinnedResourceArrays;
    std::vector<jbyte*> pinnedResourceBytes;
    try {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        const jsize resourceCount = env->GetArrayLength(resourceData);
        if (resourceCount != env->GetArrayLength(resourceIndices) ||
            resourceCount != env->GetArrayLength(resourceTypes) ||
            resourceCount > 256 || resourceScopeId < 0) {
            throw std::invalid_argument("Invalid B62 resource context");
        }

        std::vector<jint> indices(static_cast<size_t>(resourceCount));
        std::vector<jint> types(static_cast<size_t>(resourceCount));
        if (resourceCount > 0) {
            env->GetIntArrayRegion(resourceIndices, 0, resourceCount, indices.data());
            env->GetIntArrayRegion(resourceTypes, 0, resourceCount, types.data());
        }
        pinnedResourceArrays.reserve(static_cast<size_t>(resourceCount));
        pinnedResourceBytes.reserve(static_cast<size_t>(resourceCount));
        std::vector<aribcaption::B62ResourceView> resourceViews;
        resourceViews.reserve(static_cast<size_t>(resourceCount));
        for (jsize index = 0; index < resourceCount; ++index) {
            if (indices[static_cast<size_t>(index)] < 0) {
                throw std::invalid_argument("Invalid B62 resource index");
            }
            auto resourceArray = static_cast<jbyteArray>(
                env->GetObjectArrayElement(resourceData, index));
            if (!resourceArray) {
                throw std::invalid_argument("Missing B62 resource data");
            }
            const jsize resourceSize = env->GetArrayLength(resourceArray);
            jbyte* resourceBytes = resourceSize > 0
                ? env->GetByteArrayElements(resourceArray, nullptr)
                : nullptr;
            pinnedResourceArrays.push_back(resourceArray);
            pinnedResourceBytes.push_back(resourceBytes);
            if (resourceSize > 0 && !resourceBytes) {
                throw std::bad_alloc();
            }
            aribcaption::B62ResourceView view;
            view.index = static_cast<uint32_t>(indices[static_cast<size_t>(index)]);
            view.data = reinterpret_cast<const uint8_t*>(resourceBytes);
            view.size = static_cast<size_t>(resourceSize);
            view.mime_type = b62ResourceMimeType(types[static_cast<size_t>(index)]);
            resourceViews.push_back(view);
        }

        aribcaption::B62DocumentDecodeResult decoded;
        aribcaption::B62DecodeOptions options;
        options.document_pts = static_cast<int64_t>(ptsMs);
        options.discontinuity = discontinuity == JNI_TRUE;
        switch (operationMode) {
        case 0:
            options.operation_mode = aribcaption::B62OperationMode::kLive;
            break;
        case 2:
            options.operation_mode = aribcaption::B62OperationMode::kProgram;
            break;
        default:
            options.operation_mode = aribcaption::B62OperationMode::kSegment;
            break;
        }
        switch (timingMode) {
        case 2:
            if (referenceStartPtsMs != std::numeric_limits<jlong>::min()) {
                options.time_base_pts = static_cast<int64_t>(referenceStartPtsMs);
            } else {
                options.align_earliest_to_document_pts = true;
            }
            break;
        case 3:
            options.time_base_pts = static_cast<int64_t>(ptsMs);
            break;
        case 8:
        case 15:
            options.ignore_document_timing = true;
            break;
        default:
            options.align_earliest_to_document_pts = true;
            break;
        }
        aribcaption::B62ResourceContextView resourceContext;
        resourceContext.scope_id = static_cast<uint64_t>(resourceScopeId);
        resourceContext.resources = resourceViews.data();
        resourceContext.resource_count = resourceViews.size();
        const auto status = ctx->b62Decoder->DecodeDocument(
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length),
            options,
            resourceContext,
            decoded);
        if (status == aribcaption::B62DecodeStatus::kGotCaption) {
            ctx->b62Renderer->Flush();
            struct RenderPlan {
                int64_t pts;
                int64_t duration;
                std::vector<CaptionRegionGeometry> regions;
                bool hasRegions;
            };
            std::vector<RenderPlan> plans;
            plans.reserve(decoded.captions.size());
            for (const aribcaption::Caption& caption : decoded.captions) {
                plans.push_back(RenderPlan{
                    caption.pts,
                    caption.wait_duration,
                    mapCaptionContentBoundsToRenderFrame(caption),
                    !caption.regions.empty()});
            }
            if (!ctx->b62Renderer->AppendB62Document(std::move(decoded))) {
                throw std::runtime_error("Failed to append B62 document");
            }
            for (RenderPlan& plan : plans) {
                aribcaption::RenderResult result;
                if (!plan.hasRegions) {
                    result.pts = plan.pts;
                    result.duration = plan.duration;
                } else {
                    const int64_t renderPts = plan.pts == aribcaption::PTS_NOPTS
                        ? static_cast<int64_t>(ptsMs)
                        : plan.pts;
                    const auto renderStatus = ctx->b62Renderer->Render(renderPts, result);
                    if (renderStatus != aribcaption::RenderStatus::kGotImage &&
                        renderStatus != aribcaption::RenderStatus::kGotImageUnchanged) {
                        continue;
                    }
                    // The renderer is configured to merge regions, so one cue must never
                    // retain more RGBA storage than a complete 1080p frame. Reject malformed
                    // or unexpectedly large results before allocating the Java Bitmap copy.
                    if (!isB62RenderResultWithinBudget(result)) continue;
                }

                // Convert one result at a time. Keeping every native RenderResult until the
                // whole document finished temporarily retained all RGBA buffers alongside
                // their Java Bitmaps, which amplified memory peaks on low-memory TVs.
                jobject cue = renderB62ResultToCue(
                    env,
                    result,
                    ptsMs,
                    &plan.regions,
                    ctx->captionType);
                if (cue != nullptr) cues.push_back(cue);
            }
        }
    } catch (...) {
        // Komorebi is built with exceptions enabled, so allocations made by ordinary STL
        // containers can still fail here. Keep that application-level policy out of
        // libaribcaption's allocator and drop this expendable subtitle document at JNI.
        for (jobject cue : cues) env->DeleteLocalRef(cue);
        cues.clear();
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->b62Renderer->Flush();
    }
    for (size_t index = 0; index < pinnedResourceArrays.size(); ++index) {
        if (pinnedResourceBytes[index]) {
            env->ReleaseByteArrayElements(
                pinnedResourceArrays[index],
                pinnedResourceBytes[index],
                JNI_ABORT);
        }
        env->DeleteLocalRef(pinnedResourceArrays[index]);
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(cues.size()), cueClass, nullptr);
    for (size_t index = 0; index < cues.size(); ++index) {
        env->SetObjectArrayElement(result, static_cast<jsize>(index), cues[index]);
        env->DeleteLocalRef(cues[index]);
    }
    env->DeleteLocalRef(cueClass);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_getCaptionLanguageCodes(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) return env->NewIntArray(0);

    jint codes[ARIBCC_LANGUAGEID_MAX] = {};
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        codes[0] = static_cast<jint>(aribcc_decoder_query_iso6392_language_code(
            ctx->decoder,
            ARIBCC_LANGUAGEID_FIRST));
        codes[1] = static_cast<jint>(aribcc_decoder_query_iso6392_language_code(
            ctx->decoder,
            ARIBCC_LANGUAGEID_SECOND));
    }

    jsize count = codes[1] != 0 ? 2 : (codes[0] != 0 ? 1 : 0);
    jintArray result = env->NewIntArray(count);
    if (count > 0) env->SetIntArrayRegion(result, 0, count, codes);
    return result;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_switchCaptionLanguage(JNIEnv *env, jobject thiz, jlong handle, jint languageId) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder || languageId < ARIBCC_LANGUAGEID_FIRST || languageId > ARIBCC_LANGUAGEID_MAX) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);
    aribcc_decoder_switch_language(
        ctx->decoder,
        static_cast<aribcc_languageid_t>(languageId));
    if (ctx->renderer) aribcc_renderer_flush(ctx->renderer);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_flushCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    aribcc_decoder_flush(ctx->decoder);
    if (ctx->renderer) aribcc_renderer_flush(ctx->renderer);
    if (ctx->b62Decoder) ctx->b62Decoder->Reset();
    if (ctx->b62Renderer) ctx->b62Renderer->Flush();
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->b62Renderer.reset();
        ctx->b62Decoder.reset();
        if (ctx->renderer) aribcc_renderer_free(ctx->renderer);
        if (ctx->decoder) aribcc_decoder_free(ctx->decoder);
        if (ctx->context) aribcc_context_free(ctx->context);
        ctx->renderer = nullptr;
        ctx->decoder = nullptr;
        ctx->context = nullptr;
    }
    delete ctx;
}

JNIEXPORT jlong JNICALL
Java_com_beeregg2001_komorebi_NativeLib_openTlvDemuxer(
    JNIEnv* env,
    jobject thiz,
    jobject callback,
    jint preferredVideoPacketId,
    jboolean buildRecordingIndex,
    jboolean exposeAllVideoTracks) {
    if (callback == nullptr) return 0;
    return reinterpret_cast<jlong>(new TlvDemuxContext(
        env,
        callback,
        static_cast<int>(preferredVideoPacketId),
        buildRecordingIndex == JNI_TRUE,
        exposeAllVideoTracks == JNI_TRUE));
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_pushTlvData(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray data,
    jint length) {
    auto* ctx = reinterpret_cast<TlvDemuxContext*>(handle);
    if (ctx == nullptr || data == nullptr || length <= 0) return;

    const jsize arrayLength = env->GetArrayLength(data);
    const jsize safeLength = std::min(arrayLength, static_cast<jsize>(length));
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return;
    ctx->push(
        env,
        reinterpret_cast<const std::uint8_t*>(bytes),
        static_cast<std::size_t>(safeLength));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_flushTlvDemuxer(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
    auto* ctx = reinterpret_cast<TlvDemuxContext*>(handle);
    if (ctx != nullptr) ctx->flush(env);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_resetTlvDemuxer(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
    auto* ctx = reinterpret_cast<TlvDemuxContext*>(handle);
    if (ctx != nullptr) ctx->reset(env);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_repositionTlvDemuxer(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong inputOffset) {
    auto* ctx = reinterpret_cast<TlvDemuxContext*>(handle);
    if (ctx != nullptr) ctx->reposition(static_cast<std::uint64_t>(std::max<jlong>(0, inputOffset)));
}

JNIEXPORT jlongArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_getTlvSeekPoints(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong targetUs) {
    auto* ctx = reinterpret_cast<TlvDemuxContext*>(handle);
    jlongArray result = env->NewLongArray(4);
    if (result == nullptr) return nullptr;
    std::array<jlong, 4> values{-1, -1, -1, -1};
    if (ctx != nullptr) {
        const auto points = ctx->seekPoints(static_cast<std::int64_t>(targetUs));
        for (std::size_t index = 0; index < values.size(); ++index) {
            values[index] = static_cast<jlong>(points[index]);
        }
    }
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeTlvDemuxer(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
    auto* ctx = reinterpret_cast<TlvDemuxContext*>(handle);
    if (ctx == nullptr) return;
    ctx->release(env);
    delete ctx;
}

JNIEXPORT jlong JNICALL
Java_com_beeregg2001_komorebi_NativeLib_openTlvDurationProbe(
    JNIEnv* env,
    jobject thiz,
    jlong sourceSize,
    jint preferredVideoPacketId) {
    if (sourceSize <= 0) return 0;

    auto* probe = new aribtlv::DurationProbe();
    aribtlv::DurationProbeOptions options;
    if (preferredVideoPacketId >= 0) {
        options.video_packet_id = static_cast<std::uint16_t>(preferredVideoPacketId);
    }
    if (!probe->begin(static_cast<std::uint64_t>(sourceSize), options)) {
        delete probe;
        return 0;
    }
    return reinterpret_cast<jlong>(probe);
}

JNIEXPORT jlongArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_getTlvDurationProbeNextRange(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
    auto* probe = reinterpret_cast<aribtlv::DurationProbe*>(handle);
    jlongArray result = env->NewLongArray(3);
    if (result == nullptr) return nullptr;

    std::array<jlong, 3> values{-1, -1, -1};
    if (probe != nullptr) {
        const auto request = probe->nextRange();
        if (request.has_value()) {
            values[0] = static_cast<jlong>(request->request_id);
            values[1] = static_cast<jlong>(request->offset);
            values[2] = static_cast<jlong>(request->length);
        }
    }
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_beeregg2001_komorebi_NativeLib_pushTlvDurationProbeRange(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong requestId,
    jlong absoluteOffset,
    jbyteArray data,
    jint length,
    jboolean endOfRange) {
    auto* probe = reinterpret_cast<aribtlv::DurationProbe*>(handle);
    if (probe == nullptr || data == nullptr || length < 0) return JNI_FALSE;

    const jsize arrayLength = env->GetArrayLength(data);
    const jsize safeLength = std::min(arrayLength, static_cast<jsize>(length));
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    const bool accepted = probe->pushRange(
        static_cast<std::uint64_t>(requestId),
        static_cast<std::uint64_t>(std::max<jlong>(0, absoluteOffset)),
        reinterpret_cast<const std::uint8_t*>(bytes),
        static_cast<std::size_t>(safeLength),
        endOfRange == JNI_TRUE);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_beeregg2001_komorebi_NativeLib_getTlvDurationProbeResult(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
    auto* probe = reinterpret_cast<aribtlv::DurationProbe*>(handle);
    jlongArray result = env->NewLongArray(4);
    if (result == nullptr) return nullptr;

    std::array<jlong, 4> values{-1, -1, -1, 0};
    if (probe != nullptr) {
        values[0] = static_cast<jlong>(probe->state());
        values[1] = static_cast<jlong>(probe->failure());
        const auto duration = probe->duration();
        if (duration.status == aribtlv::DurationStatus::Complete &&
            duration.value.timescale != 0) {
            values[2] = static_cast<jlong>(
                duration.value.value * 1000000LL /
                static_cast<std::int64_t>(duration.value.timescale));
        }
        values[3] = static_cast<jlong>(probe->transferredBytes());
    }
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeTlvDurationProbe(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
    delete reinterpret_cast<aribtlv::DurationProbe*>(handle);
}

}
