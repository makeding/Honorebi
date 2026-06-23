#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <memory>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <deque>
#include <dlfcn.h>
#include <sstream>
#include <android/log.h>

// tsreadex コアヘッダ
#include "servicefilter.hpp"
#include "id3conv.hpp"
#include "util.hpp"
#include "traceb24.hpp"

namespace {

constexpr const char* LOG_TAG = "KomorebiNative";
constexpr int ARIBCC_ENCODING_SCHEME_ARIB_STD_B24_JIS = 1;
constexpr int ARIBCC_CAPTIONTYPE_CAPTION = 0x80;
constexpr int ARIBCC_PROFILE_A = 0x0008;
constexpr int ARIBCC_LANGUAGEID_FIRST = 1;
constexpr int ARIBCC_DECODE_STATUS_GOT_CAPTION = 2;
constexpr int ARIBCC_CAPTIONFLAGS_CLEARSCREEN = 1;
constexpr int64_t ARIBCC_DURATION_INDEFINITE = static_cast<int64_t>(0x7fffffffffffffffLL);
constexpr int ARIBCC_RENDER_STATUS_GOT_IMAGE = 2;
constexpr int ARIBCC_RENDER_STATUS_GOT_IMAGE_UNCHANGED = 3;
constexpr int ARIBCC_CAPTION_STORAGE_POLICY_MINIMUM = 0;
constexpr int ARIBCC_FONTPROVIDER_TYPE_AUTO = 0;
constexpr int ARIBCC_TEXTRENDERER_TYPE_AUTO = 0;
constexpr int ARIBCC_RENDER_FRAME_WIDTH = 1920;
constexpr int ARIBCC_RENDER_FRAME_HEIGHT = 1080;

struct aribcc_context_t;
struct aribcc_decoder_t;
struct aribcc_renderer_t;
struct aribcc_drcsmap_t;

typedef unsigned int aribcc_color_t;

struct aribcc_caption_char_t {
    int type;
    uint32_t codepoint;
    uint32_t pua_codepoint;
    uint32_t drcs_code;
    int x;
    int y;
    int char_width;
    int char_height;
    int char_horizontal_spacing;
    int char_vertical_spacing;
    float char_horizontal_scale;
    float char_vertical_scale;
    aribcc_color_t text_color;
    aribcc_color_t back_color;
    aribcc_color_t stroke_color;
    int style;
    int enclosure_style;
    char u8str[8];
};

struct aribcc_caption_region_t {
    int x;
    int y;
    int width;
    int height;
    bool is_ruby;
    aribcc_caption_char_t* chars;
    uint32_t char_count;
};

struct aribcc_caption_t {
    int type;
    int flags;
    uint32_t iso6392_language_code;
    char* text;
    aribcc_caption_region_t* regions;
    uint32_t region_count;
    aribcc_drcsmap_t* drcs_map;
    int64_t pts;
    int64_t wait_duration;
    int plane_width;
    int plane_height;
    bool has_builtin_sound;
    uint8_t builtin_sound_id;
};

struct aribcc_image_t {
    int width;
    int height;
    int stride;
    int dst_x;
    int dst_y;
    int pixel_format;
    uint8_t* bitmap;
    uint32_t bitmap_size;
};

struct aribcc_render_result_t {
    int64_t pts;
    int64_t duration;
    aribcc_image_t* images;
    uint32_t image_count;
};

using aribcc_context_alloc_fn = aribcc_context_t* (*)();
using aribcc_context_free_fn = void (*)(aribcc_context_t*);
using aribcc_decoder_alloc_fn = aribcc_decoder_t* (*)(aribcc_context_t*);
using aribcc_decoder_free_fn = void (*)(aribcc_decoder_t*);
using aribcc_decoder_initialize_fn = bool (*)(aribcc_decoder_t*, int, int, int, int);
using aribcc_decoder_decode_fn = int (*)(aribcc_decoder_t*, const uint8_t*, size_t, int64_t, aribcc_caption_t*);
using aribcc_decoder_flush_fn = void (*)(aribcc_decoder_t*);
using aribcc_caption_cleanup_fn = void (*)(aribcc_caption_t*);
using aribcc_renderer_alloc_fn = aribcc_renderer_t* (*)(aribcc_context_t*);
using aribcc_renderer_free_fn = void (*)(aribcc_renderer_t*);
using aribcc_renderer_initialize_fn = bool (*)(aribcc_renderer_t*, int, int, int);
using aribcc_renderer_set_frame_size_fn = bool (*)(aribcc_renderer_t*, int, int);
using aribcc_renderer_set_margins_fn = bool (*)(aribcc_renderer_t*, int, int, int, int);
using aribcc_renderer_set_storage_policy_fn = void (*)(aribcc_renderer_t*, int, size_t);
using aribcc_renderer_set_force_stroke_text_fn = void (*)(aribcc_renderer_t*, bool);
using aribcc_renderer_set_replace_drcs_fn = void (*)(aribcc_renderer_t*, bool);
using aribcc_renderer_set_merge_region_images_fn = void (*)(aribcc_renderer_t*, bool);
using aribcc_renderer_append_caption_fn = bool (*)(aribcc_renderer_t*, const aribcc_caption_t*);
using aribcc_renderer_render_fn = int (*)(aribcc_renderer_t*, int64_t, aribcc_render_result_t*);
using aribcc_renderer_flush_fn = void (*)(aribcc_renderer_t*);
using aribcc_render_result_cleanup_fn = void (*)(aribcc_render_result_t*);

class AribCaptionApi {
public:
    void* library = nullptr;
    aribcc_context_alloc_fn contextAlloc = nullptr;
    aribcc_context_free_fn contextFree = nullptr;
    aribcc_decoder_alloc_fn decoderAlloc = nullptr;
    aribcc_decoder_free_fn decoderFree = nullptr;
    aribcc_decoder_initialize_fn decoderInitialize = nullptr;
    aribcc_decoder_decode_fn decoderDecode = nullptr;
    aribcc_decoder_flush_fn decoderFlush = nullptr;
    aribcc_caption_cleanup_fn captionCleanup = nullptr;
    aribcc_renderer_alloc_fn rendererAlloc = nullptr;
    aribcc_renderer_free_fn rendererFree = nullptr;
    aribcc_renderer_initialize_fn rendererInitialize = nullptr;
    aribcc_renderer_set_frame_size_fn rendererSetFrameSize = nullptr;
    aribcc_renderer_set_margins_fn rendererSetMargins = nullptr;
    aribcc_renderer_set_storage_policy_fn rendererSetStoragePolicy = nullptr;
    aribcc_renderer_set_force_stroke_text_fn rendererSetForceStrokeText = nullptr;
    aribcc_renderer_set_replace_drcs_fn rendererSetReplaceDrcs = nullptr;
    aribcc_renderer_set_merge_region_images_fn rendererSetMergeRegionImages = nullptr;
    aribcc_renderer_append_caption_fn rendererAppendCaption = nullptr;
    aribcc_renderer_render_fn rendererRender = nullptr;
    aribcc_renderer_flush_fn rendererFlush = nullptr;
    aribcc_render_result_cleanup_fn renderResultCleanup = nullptr;
    bool attemptedLoad = false;

    bool available() {
        std::lock_guard<std::mutex> lock(mutex);
        if (attemptedLoad) return library != nullptr;
        attemptedLoad = true;
        library = dlopen("libaribcaption.so", RTLD_LAZY);
        if (!library) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "libaribcaption.so is not available: %s", dlerror());
            return false;
        }

        contextAlloc = symbol<aribcc_context_alloc_fn>("aribcc_context_alloc");
        contextFree = symbol<aribcc_context_free_fn>("aribcc_context_free");
        decoderAlloc = symbol<aribcc_decoder_alloc_fn>("aribcc_decoder_alloc");
        decoderFree = symbol<aribcc_decoder_free_fn>("aribcc_decoder_free");
        decoderInitialize = symbol<aribcc_decoder_initialize_fn>("aribcc_decoder_initialize");
        decoderDecode = symbol<aribcc_decoder_decode_fn>("aribcc_decoder_decode");
        decoderFlush = symbol<aribcc_decoder_flush_fn>("aribcc_decoder_flush");
        captionCleanup = symbol<aribcc_caption_cleanup_fn>("aribcc_caption_cleanup");
        rendererAlloc = symbol<aribcc_renderer_alloc_fn>("aribcc_renderer_alloc");
        rendererFree = symbol<aribcc_renderer_free_fn>("aribcc_renderer_free");
        rendererInitialize = symbol<aribcc_renderer_initialize_fn>("aribcc_renderer_initialize");
        rendererSetFrameSize = symbol<aribcc_renderer_set_frame_size_fn>("aribcc_renderer_set_frame_size");
        rendererSetMargins = symbol<aribcc_renderer_set_margins_fn>("aribcc_renderer_set_margins");
        rendererSetStoragePolicy = symbol<aribcc_renderer_set_storage_policy_fn>("aribcc_renderer_set_storage_policy");
        rendererSetForceStrokeText = symbol<aribcc_renderer_set_force_stroke_text_fn>("aribcc_renderer_set_force_stroke_text");
        rendererSetReplaceDrcs = symbol<aribcc_renderer_set_replace_drcs_fn>("aribcc_renderer_set_replace_drcs");
        rendererSetMergeRegionImages = symbol<aribcc_renderer_set_merge_region_images_fn>("aribcc_renderer_set_merge_region_images");
        rendererAppendCaption = symbol<aribcc_renderer_append_caption_fn>("aribcc_renderer_append_caption");
        rendererRender = symbol<aribcc_renderer_render_fn>("aribcc_renderer_render");
        rendererFlush = symbol<aribcc_renderer_flush_fn>("aribcc_renderer_flush");
        renderResultCleanup = symbol<aribcc_render_result_cleanup_fn>("aribcc_render_result_cleanup");

        if (!contextAlloc || !contextFree || !decoderAlloc || !decoderFree ||
            !decoderInitialize || !decoderDecode || !decoderFlush || !captionCleanup ||
            !rendererAlloc || !rendererFree || !rendererInitialize || !rendererSetFrameSize ||
            !rendererSetMargins || !rendererSetStoragePolicy || !rendererSetForceStrokeText ||
            !rendererSetReplaceDrcs || !rendererSetMergeRegionImages || !rendererAppendCaption ||
            !rendererRender || !rendererFlush || !renderResultCleanup) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "libaribcaption.so is missing required symbols");
            dlclose(library);
            library = nullptr;
            return false;
        }
        return true;
    }

private:
    std::mutex mutex;

    template<typename T>
    T symbol(const char* name) {
        return reinterpret_cast<T>(dlsym(library, name));
    }
};

struct AribCaptionDecoderContext {
    AribCaptionApi* api = nullptr;
    aribcc_context_t* context = nullptr;
    aribcc_decoder_t* decoder = nullptr;
    aribcc_renderer_t* renderer = nullptr;
    std::mutex mutex;
};

AribCaptionApi& captionApi() {
    static AribCaptionApi api;
    return api;
}

void appendJsonEscaped(std::ostringstream& out, const char* text) {
    out << '"';
    if (text) {
        for (const unsigned char* p = reinterpret_cast<const unsigned char*>(text); *p; ++p) {
            switch (*p) {
                case '"': out << "\\\""; break;
                case '\\': out << "\\\\"; break;
                case '\b': out << "\\b"; break;
                case '\f': out << "\\f"; break;
                case '\n': out << "\\n"; break;
                case '\r': out << "\\r"; break;
                case '\t': out << "\\t"; break;
                default:
                    if (*p < 0x20) {
                        out << "\\u00";
                        const char* hex = "0123456789abcdef";
                        out << hex[(*p >> 4) & 0x0f] << hex[*p & 0x0f];
                    } else {
                        out << *p;
                    }
                    break;
            }
        }
    }
    out << '"';
}

std::string utf8FromCodepoint(uint32_t codepoint) {
    std::string out;
    if (codepoint == 0) return out;
    if (codepoint <= 0x7f) {
        out.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0x10ffff) {
        out.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
    return out;
}

std::string base64Encode(const uint8_t* data, size_t size) {
    static constexpr char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((size + 2) / 3) * 4);
    for (size_t i = 0; i < size; i += 3) {
        uint32_t chunk = data[i] << 16;
        if (i + 1 < size) chunk |= data[i + 1] << 8;
        if (i + 2 < size) chunk |= data[i + 2];
        out.push_back(table[(chunk >> 18) & 0x3f]);
        out.push_back(table[(chunk >> 12) & 0x3f]);
        out.push_back(i + 1 < size ? table[(chunk >> 6) & 0x3f] : '=');
        out.push_back(i + 2 < size ? table[chunk & 0x3f] : '=');
    }
    return out;
}

std::string renderResultToJson(aribcc_render_result_t& result, int64_t fallbackPtsMs) {
    std::ostringstream out;
    int64_t duration = result.duration == ARIBCC_DURATION_INDEFINITE ? -1 : result.duration;
    int64_t pts = result.pts < 0 ? fallbackPtsMs : result.pts;
    out << "{\"ptsMs\":" << pts
        << ",\"durationMs\":" << duration
        << ",\"clearScreen\":" << (result.image_count == 0 ? "true" : "false")
        << ",\"planeWidth\":" << ARIBCC_RENDER_FRAME_WIDTH
        << ",\"planeHeight\":" << ARIBCC_RENDER_FRAME_HEIGHT
        << ",\"images\":[";

    for (uint32_t i = 0; i < result.image_count; ++i) {
        if (i > 0) out << ',';
        aribcc_image_t& image = result.images[i];
        std::string rgba = image.bitmap && image.bitmap_size > 0
            ? base64Encode(image.bitmap, image.bitmap_size)
            : "";
        out << "{\"x\":" << image.dst_x
            << ",\"y\":" << image.dst_y
            << ",\"width\":" << image.width
            << ",\"height\":" << image.height
            << ",\"stride\":" << image.stride
            << ",\"rgba\":";
        appendJsonEscaped(out, rgba.c_str());
        out << '}';
    }

    out << "]}";
    return out.str();
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
    AribCaptionApi& api = captionApi();
    if (!api.available()) return 0;

    auto* ctx = new AribCaptionDecoderContext();
    ctx->api = &api;
    ctx->context = api.contextAlloc();
    if (!ctx->context) {
        delete ctx;
        return 0;
    }
    ctx->decoder = api.decoderAlloc(ctx->context);
    if (!ctx->decoder) {
        api.contextFree(ctx->context);
        delete ctx;
        return 0;
    }
    if (!api.decoderInitialize(
            ctx->decoder,
            ARIBCC_ENCODING_SCHEME_ARIB_STD_B24_JIS,
            ARIBCC_CAPTIONTYPE_CAPTION,
            ARIBCC_PROFILE_A,
            ARIBCC_LANGUAGEID_FIRST)) {
        api.decoderFree(ctx->decoder);
        api.contextFree(ctx->context);
        delete ctx;
        return 0;
    }
    ctx->renderer = api.rendererAlloc(ctx->context);
    if (!ctx->renderer) {
        api.decoderFree(ctx->decoder);
        api.contextFree(ctx->context);
        delete ctx;
        return 0;
    }
    if (!api.rendererInitialize(
            ctx->renderer,
            ARIBCC_CAPTIONTYPE_CAPTION,
            ARIBCC_FONTPROVIDER_TYPE_AUTO,
            ARIBCC_TEXTRENDERER_TYPE_AUTO)) {
        api.rendererFree(ctx->renderer);
        api.decoderFree(ctx->decoder);
        api.contextFree(ctx->context);
        delete ctx;
        return 0;
    }
    api.rendererSetFrameSize(ctx->renderer, ARIBCC_RENDER_FRAME_WIDTH, ARIBCC_RENDER_FRAME_HEIGHT);
    api.rendererSetMargins(ctx->renderer, 0, 0, 0, 0);
    api.rendererSetStoragePolicy(ctx->renderer, ARIBCC_CAPTION_STORAGE_POLICY_MINIMUM, 0);
    api.rendererSetForceStrokeText(ctx->renderer, true);
    api.rendererSetReplaceDrcs(ctx->renderer, true);
    api.rendererSetMergeRegionImages(ctx->renderer, false);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
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
        status = ctx->api->decoderDecode(
            ctx->decoder,
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length),
            static_cast<int64_t>(ptsMs),
            &caption);
        if (status == ARIBCC_DECODE_STATUS_GOT_CAPTION) {
            ctx->api->rendererAppendCaption(ctx->renderer, &caption);
            int renderStatus = ctx->api->rendererRender(ctx->renderer, static_cast<int64_t>(ptsMs), &renderResult);
            if (renderStatus != ARIBCC_RENDER_STATUS_GOT_IMAGE &&
                renderStatus != ARIBCC_RENDER_STATUS_GOT_IMAGE_UNCHANGED) {
                ctx->api->captionCleanup(&caption);
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return nullptr;
            }
        }
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (status != ARIBCC_DECODE_STATUS_GOT_CAPTION) {
        return nullptr;
    }

    std::string json = renderResultToJson(renderResult, ptsMs);
    ctx->api->renderResultCleanup(&renderResult);
    ctx->api->captionCleanup(&caption);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_flushCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    ctx->api->decoderFlush(ctx->decoder);
    if (ctx->renderer) ctx->api->rendererFlush(ctx->renderer);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (ctx->renderer) ctx->api->rendererFree(ctx->renderer);
        if (ctx->decoder) ctx->api->decoderFree(ctx->decoder);
        if (ctx->context) ctx->api->contextFree(ctx->context);
        ctx->renderer = nullptr;
        ctx->decoder = nullptr;
        ctx->context = nullptr;
    }
    delete ctx;
}

}
