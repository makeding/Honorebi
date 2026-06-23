#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <memory>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <deque>
#include <sstream>

#include <aribcaption/aribcaption.h>

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
    std::mutex mutex;
};

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

    std::string json = renderResultToJson(renderResult, ptsMs);
    aribcc_render_result_cleanup(&renderResult);
    aribcc_caption_cleanup(&caption);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_flushCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx || !ctx->decoder) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    aribcc_decoder_flush(ctx->decoder);
    if (ctx->renderer) aribcc_renderer_flush(ctx->renderer);
}

JNIEXPORT void JNICALL
Java_com_beeregg2001_komorebi_NativeLib_closeCaptionDecoder(JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<AribCaptionDecoderContext*>(handle);
    if (!ctx) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (ctx->renderer) aribcc_renderer_free(ctx->renderer);
        if (ctx->decoder) aribcc_decoder_free(ctx->decoder);
        if (ctx->context) aribcc_context_free(ctx->context);
        ctx->renderer = nullptr;
        ctx->decoder = nullptr;
        ctx->context = nullptr;
    }
    delete ctx;
}

}
