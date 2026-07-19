#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <memory>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <deque>
#include <array>
#include <limits>

#include <aribcaption/aribcaption.h>
#include <aribcaption/aribcaption.hpp>
#include <tlvdemux/demuxer.hpp>
#include <tlvdemux/recording.hpp>

// tsreadex コアヘッダ
#include "servicefilter.hpp"
#include "id3conv.hpp"
#include "util.hpp"
#include "traceb24.hpp"

namespace {

constexpr int ARIBCC_RENDER_FRAME_WIDTH = 1920;
constexpr int ARIBCC_RENDER_FRAME_HEIGHT = 1080;

struct AribCaptionDecoderContext {
    aribcc_context_t* context = nullptr;
    aribcc_decoder_t* decoder = nullptr;
    aribcc_renderer_t* renderer = nullptr;
    std::unique_ptr<aribcaption::Context> b62Context;
    std::unique_ptr<aribcaption::B62Decoder> b62Decoder;
    std::unique_ptr<aribcaption::Renderer> b62Renderer;
    std::mutex mutex;
};

jobject renderResultToCue(JNIEnv* env, aribcc_render_result_t& result, int64_t fallbackPtsMs) {
    int64_t duration = result.duration == ARIBCC_DURATION_INDEFINITE ? -1 : result.duration;
    int64_t pts = result.pts < 0 ? fallbackPtsMs : result.pts;

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "(I)V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject images = env->NewObject(arrayListClass, arrayListCtor, static_cast<jint>(result.image_count));

    jclass imageClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionImage");
    jmethodID imageCtor = env->GetMethodID(imageClass, "<init>", "(IIIII[B)V");

    for (uint32_t i = 0; i < result.image_count; ++i) {
        aribcc_image_t& image = result.images[i];
        jsize bitmapSize = image.bitmap && image.bitmap_size > 0
            ? static_cast<jsize>(image.bitmap_size)
            : 0;
        jbyteArray rgba = env->NewByteArray(bitmapSize);
        if (bitmapSize > 0) {
            env->SetByteArrayRegion(
                rgba,
                0,
                bitmapSize,
                reinterpret_cast<const jbyte*>(image.bitmap));
        }
        jobject captionImage = env->NewObject(
            imageClass,
            imageCtor,
            static_cast<jint>(image.dst_x),
            static_cast<jint>(image.dst_y),
            static_cast<jint>(image.width),
            static_cast<jint>(image.height),
            static_cast<jint>(image.stride),
            rgba);
        env->CallBooleanMethod(images, arrayListAdd, captionImage);
        env->DeleteLocalRef(captionImage);
        env->DeleteLocalRef(rgba);
    }

    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    jmethodID cueCtor = env->GetMethodID(cueClass, "<init>", "(JJZIILjava/util/List;Z)V");
    jobject cue = env->NewObject(
        cueClass,
        cueCtor,
        static_cast<jlong>(pts),
        static_cast<jlong>(duration),
        result.image_count == 0 ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(ARIBCC_RENDER_FRAME_WIDTH),
        static_cast<jint>(ARIBCC_RENDER_FRAME_HEIGHT),
        images,
        JNI_FALSE);

    env->DeleteLocalRef(images);
    env->DeleteLocalRef(imageClass);
    env->DeleteLocalRef(arrayListClass);
    env->DeleteLocalRef(cueClass);
    return cue;
}

jobject renderResultToCue(JNIEnv* env, aribcaption::RenderResult& result, int64_t fallbackPtsMs) {
    const int64_t duration = result.duration == aribcaption::DURATION_INDEFINITE ? -1 : result.duration;
    const int64_t pts = result.pts == aribcaption::PTS_NOPTS ? fallbackPtsMs : result.pts;

    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "(I)V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jobject images = env->NewObject(
        arrayListClass,
        arrayListCtor,
        static_cast<jint>(result.images.size()));

    jclass imageClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionImage");
    jmethodID imageCtor = env->GetMethodID(imageClass, "<init>", "(IIIII[B)V");

    for (auto& image : result.images) {
        const jsize bitmapSize = static_cast<jsize>(image.bitmap.size());
        jbyteArray rgba = env->NewByteArray(bitmapSize);
        if (bitmapSize > 0) {
            env->SetByteArrayRegion(
                rgba,
                0,
                bitmapSize,
                reinterpret_cast<const jbyte*>(image.bitmap.data()));
        }
        jobject captionImage = env->NewObject(
            imageClass,
            imageCtor,
            static_cast<jint>(image.dst_x),
            static_cast<jint>(image.dst_y),
            static_cast<jint>(image.width),
            static_cast<jint>(image.height),
            static_cast<jint>(image.stride),
            rgba);
        env->CallBooleanMethod(images, arrayListAdd, captionImage);
        env->DeleteLocalRef(captionImage);
        env->DeleteLocalRef(rgba);
    }

    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    jmethodID cueCtor = env->GetMethodID(cueClass, "<init>", "(JJZIILjava/util/List;Z)V");
    jobject cue = env->NewObject(
        cueClass,
        cueCtor,
        static_cast<jlong>(pts),
        static_cast<jlong>(duration),
        result.images.empty() ? JNI_TRUE : JNI_FALSE,
        static_cast<jint>(ARIBCC_RENDER_FRAME_WIDTH),
        static_cast<jint>(ARIBCC_RENDER_FRAME_HEIGHT),
        images,
        JNI_FALSE);

    env->DeleteLocalRef(images);
    env->DeleteLocalRef(imageClass);
    env->DeleteLocalRef(arrayListClass);
    env->DeleteLocalRef(cueClass);
    return cue;
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

class TlvDemuxContext final : public tlvdemux::Sink {
public:
    TlvDemuxContext(JNIEnv* env, jobject callback, const int preferredVideoPacketId,
                    const bool buildRecordingIndex)
        : callback_(env->NewGlobalRef(callback)), demuxer_(*this),
          preferredVideoPacketId_(preferredVideoPacketId),
          buildRecordingIndex_(buildRecordingIndex) {
        if (buildRecordingIndex_) recordingIndex_.begin(false);
        jclass callbackClass = env->GetObjectClass(callback);
        onServiceMethod_ = env->GetMethodID(callbackClass, "onService", "(J[B)V");
        onTrackMethod_ = env->GetMethodID(
            callbackClass,
            "onTrack",
            "(JJIIILjava/lang/String;IJIIZII)V");
        onAccessUnitMethod_ = env->GetMethodID(
            callbackClass,
            "onAccessUnit",
            "(JI[BJJJJJJJJZZ)V");
        onErrorMethod_ = env->GetMethodID(
            callbackClass,
            "onError",
            "(IJZLjava/lang/String;)V");
        env->DeleteLocalRef(callbackClass);
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

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        demuxer_.reset();
        if (buildRecordingIndex_) {
            recordingIndex_.begin(false);
        }
        selectedVideoTrackId_ = 0;
        selectedAudioTrackId_ = 0;
        selectedSubtitleTrackId_ = 0;
    }

    void reposition(const std::uint64_t inputOffset) {
        std::lock_guard<std::mutex> lock(mutex_);
        demuxer_.reposition(tlvdemux::RepositionOptions{inputOffset, true});
    }

    std::array<std::int64_t, 4> seekPoints(const std::int64_t targetUs) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::array<std::int64_t, 4> result{-1, -1, -1, -1};
        if (!buildRecordingIndex_) return result;
        const auto points = recordingIndex_.seekPointsFor(
            tlvdemux::Timestamp{targetUs, 1000000});
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
        if (callback_ != nullptr) {
            env->DeleteGlobalRef(callback_);
            callback_ = nullptr;
        }
    }

    void onService(const tlvdemux::ServiceInfo& info) override {
        if (!canCallback(onServiceMethod_)) return;
        jbyteArray packageId = makeByteArray(info.package_id);
        currentEnv_->CallVoidMethod(
            callback_,
            onServiceMethod_,
            static_cast<jlong>(info.context_id),
            packageId);
        currentEnv_->DeleteLocalRef(packageId);
    }

    void onTrack(const tlvdemux::TrackInfo& info) override {
        if (info.kind == tlvdemux::TrackKind::Video) {
            const bool matchesPreferred = preferredVideoPacketId_ < 0 ||
                info.packet_id == preferredVideoPacketId_;
            if (selectedVideoTrackId_ == 0 && matchesPreferred) {
                selectedVideoTrackId_ = info.track_id;
                demuxer_.selectTrack(tlvdemux::TrackKind::Video, info.track_id);
                if (buildRecordingIndex_) recordingIndex_.selectVideoTrack(info.track_id);
            }
            if (selectedVideoTrackId_ == 0 || info.track_id != selectedVideoTrackId_) return;
        }
        if (info.kind == tlvdemux::TrackKind::Audio) {
            const bool isUnsupported22_2 = info.audio.has_value() &&
                info.audio->channel_layout == tlvdemux::AudioChannelLayout::Channels22_2;
            if (selectedAudioTrackId_ == 0 && !isUnsupported22_2) {
                selectedAudioTrackId_ = info.track_id;
                demuxer_.selectTrack(tlvdemux::TrackKind::Audio, info.track_id);
            }
            if (selectedAudioTrackId_ == 0 || info.track_id != selectedAudioTrackId_) return;
        }
        if (info.kind == tlvdemux::TrackKind::Subtitle) {
            if (selectedSubtitleTrackId_ == 0) {
                selectedSubtitleTrackId_ = info.track_id;
                demuxer_.selectTrack(tlvdemux::TrackKind::Subtitle, info.track_id);
            }
            if (info.track_id != selectedSubtitleTrackId_) return;
        }
        if (!canCallback(onTrackMethod_)) return;
        jstring language = currentEnv_->NewStringUTF(info.language.c_str());
        const auto audioLayout = info.audio.has_value()
            ? static_cast<jint>(info.audio->channel_layout)
            : static_cast<jint>(tlvdemux::AudioChannelLayout::Unknown);
        const auto audioSampleRate = info.audio.has_value()
            ? static_cast<jint>(info.audio->sample_rate)
            : 0;
        const auto audioMainComponent = info.audio.has_value() && info.audio->main_component;
        const auto subtitleOperationMode = info.subtitle.has_value()
            ? static_cast<jint>(info.subtitle->operation_mode)
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
            audioLayout,
            audioSampleRate,
            audioMainComponent ? JNI_TRUE : JNI_FALSE,
            subtitleOperationMode,
            subtitleTimingMode);
        currentEnv_->DeleteLocalRef(language);
    }

    void onAccessUnit(tlvdemux::AccessUnit&& unit) override {
        if (buildRecordingIndex_) recordingIndex_.observe(unit);
        if (!canCallback(onAccessUnitMethod_)) return;
        jbyteArray data = makeByteArray(unit.data);
        currentEnv_->CallVoidMethod(
            callback_,
            onAccessUnitMethod_,
            static_cast<jlong>(unit.track_id),
            static_cast<jint>(unit.codec),
            data,
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
            unit.random_access ? JNI_TRUE : JNI_FALSE,
            unit.discontinuity ? JNI_TRUE : JNI_FALSE);
        currentEnv_->DeleteLocalRef(data);
    }

    void onError(const tlvdemux::Error& error) override {
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

    JNIEnv* currentEnv_ = nullptr;
    std::mutex mutex_;
    jobject callback_ = nullptr;
    jmethodID onServiceMethod_ = nullptr;
    jmethodID onTrackMethod_ = nullptr;
    jmethodID onAccessUnitMethod_ = nullptr;
    jmethodID onErrorMethod_ = nullptr;
    tlvdemux::Demuxer demuxer_;
    tlvdemux::RecordingIndex recordingIndex_;
    int preferredVideoPacketId_ = -1;
    bool buildRecordingIndex_ = false;
    std::uint64_t selectedVideoTrackId_ = 0;
    std::uint64_t selectedAudioTrackId_ = 0;
    std::uint64_t selectedSubtitleTrackId_ = 0;
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
Java_com_beeregg2001_komorebi_NativeLib_openCaptionDecoder(JNIEnv *env, jobject thiz) {
    auto* ctx = new AribCaptionDecoderContext();
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
            ARIBCC_CAPTIONTYPE_CAPTION,
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
            ARIBCC_CAPTIONTYPE_CAPTION,
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

    ctx->b62Context = std::make_unique<aribcaption::Context>();
    ctx->b62Decoder = std::make_unique<aribcaption::B62Decoder>(*ctx->b62Context);
    ctx->b62Renderer = std::make_unique<aribcaption::Renderer>(*ctx->b62Context);
    if (!ctx->b62Renderer->Initialize()) {
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
    ctx->b62Renderer->SetMergeRegionImages(false);
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
                aribcc_caption_cleanup(&caption);
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return nullptr;
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
    jboolean discontinuity) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    jclass cueClass = env->FindClass("com/beeregg2001/komorebi/ui/subtitle/NativeCaptionCue");
    if (!ctx || !ctx->b62Decoder || !ctx->b62Renderer || !data) {
        return env->NewObjectArray(0, cueClass, nullptr);
    }

    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return env->NewObjectArray(0, cueClass, nullptr);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return env->NewObjectArray(0, cueClass, nullptr);

    std::vector<aribcaption::RenderResult> rendered;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        aribcaption::B62DecodeResult decoded;
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
        const auto status = ctx->b62Decoder->Decode(
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length),
            options,
            decoded);
        if (status == aribcaption::B62DecodeStatus::kGotCaption) {
            ctx->b62Renderer->Flush();
            for (const auto& caption : decoded.captions) {
                if (caption.regions.empty()) {
                    aribcaption::RenderResult result;
                    result.pts = caption.pts;
                    result.duration = caption.wait_duration;
                    rendered.push_back(std::move(result));
                    continue;
                }
                if (!ctx->b62Renderer->AppendCaption(caption)) continue;
                aribcaption::RenderResult result;
                const int64_t renderPts = caption.pts == aribcaption::PTS_NOPTS
                    ? static_cast<int64_t>(ptsMs)
                    : caption.pts;
                const auto renderStatus = ctx->b62Renderer->Render(renderPts, result);
                if (renderStatus == aribcaption::RenderStatus::kGotImage ||
                    renderStatus == aribcaption::RenderStatus::kGotImageUnchanged) {
                    rendered.push_back(std::move(result));
                }
            }
        }
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    jobjectArray cues = env->NewObjectArray(static_cast<jsize>(rendered.size()), cueClass, nullptr);
    for (size_t index = 0; index < rendered.size(); ++index) {
        jobject cue = renderResultToCue(env, rendered[index], ptsMs);
        env->SetObjectArrayElement(cues, static_cast<jsize>(index), cue);
        env->DeleteLocalRef(cue);
    }
    env->DeleteLocalRef(cueClass);
    return cues;
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_setB62CaptionFontScale(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat scale) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->b62Decoder) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    ctx->b62Decoder->SetFontScale(static_cast<float>(scale));
    if (ctx->b62Renderer) ctx->b62Renderer->Flush();
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
        ctx->b62Context.reset();
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
    jboolean buildRecordingIndex) {
    if (callback == nullptr) return 0;
    return reinterpret_cast<jlong>(new TlvDemuxContext(
        env,
        callback,
        static_cast<int>(preferredVideoPacketId),
        buildRecordingIndex == JNI_TRUE));
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
    if (ctx != nullptr) ctx->reset();
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

}
