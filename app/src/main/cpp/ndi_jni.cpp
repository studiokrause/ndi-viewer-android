// NDI monitor for Android - native JNI bridge over NDI SDK v6
#include <jni.h>
#include <android/log.h>
#include <aaudio/AAudio.h>

#include <Processing.NDI.Lib.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "ndiviewer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------- helpers

static jstring toJString(JNIEnv* env, const char* s) {
    if (!s) s = "";
    jstring r = env->NewStringUTF(s);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        std::string clean;
        for (const char* p = s; *p; ++p)
            clean += ((unsigned char)*p < 0x80 && *p != 0) ? *p : '?';
        r = env->NewStringUTF(clean.c_str());
        if (env->ExceptionCheck()) { env->ExceptionClear(); r = nullptr; }
    }
    return r;
}

// ---------------------------------------------------------------- video conversion

static void copyRgba(const NDIlib_video_frame_v2_t& v, uint8_t* dst) {
    const int px = v.xres * 4;
    for (int y = 0; y < v.yres; ++y)
        memcpy(dst + (size_t)y * px, v.p_data + (size_t)y * v.line_stride_in_bytes, (size_t)px);
}

static void swizzleBgra(const NDIlib_video_frame_v2_t& v, uint8_t* dst) {
    const int px = v.xres * 4;
    for (int y = 0; y < v.yres; ++y) {
        const uint8_t* s = v.p_data + (size_t)y * v.line_stride_in_bytes;
        uint8_t* d = dst + (size_t)y * px;
        for (int x = 0; x < v.xres; ++x) {
            d[x * 4 + 0] = s[x * 4 + 2];
            d[x * 4 + 1] = s[x * 4 + 1];
            d[x * 4 + 2] = s[x * 4 + 0];
            d[x * 4 + 3] = s[x * 4 + 3];
        }
    }
}

// UYVY (BT.709, limited range) -> RGBA
static void uyvyToRgba(const NDIlib_video_frame_v2_t& v, uint8_t* dst) {
    for (int y = 0; y < v.yres; ++y) {
        const uint8_t* s = v.p_data + (size_t)y * v.line_stride_in_bytes;
        uint8_t* d = dst + (size_t)y * v.xres * 4;
        for (int x = 0; x < v.xres; x += 2) {
            const float u = (float)s[0] - 128.0f;
            const float y0 = (float)s[1] - 16.0f;
            const float vv = (float)s[2] - 128.0f;
            const float y1 = (float)s[3] - 16.0f;
            s += 4;
            const float r0 = 1.1644f * y0 + 1.7927f * vv;
            const float g0 = 1.1644f * y0 - 0.2132f * u - 0.5329f * vv;
            const float b0 = 1.1644f * y0 + 2.1124f * u;
            const float r1 = 1.1644f * y1 + 1.7927f * vv;
            const float g1 = 1.1644f * y1 - 0.2132f * u - 0.5329f * vv;
            const float b1 = 1.1644f * y1 + 2.1124f * u;
            auto c = [](float f) -> uint8_t {
                return (uint8_t)(f < 0.f ? 0.f : (f > 255.f ? 255.f : f));
            };
            d[0] = c(r0); d[1] = c(g0); d[2] = c(b0); d[3] = 255;
            d[4] = c(r1); d[5] = c(g1); d[6] = c(b1); d[7] = 255;
            d += 8;
        }
    }
}

static inline uint8_t clampU8(float f) {
    return (uint8_t)(f < 0.f ? 0.f : (f > 255.f ? 255.f : f));
}

// Generic YUV -> RGB (BT.709 limited) helper: Y 16-235, U/V 16-240 centered at 128
static void yuvToRgb(float y, float u, float v, uint8_t* out) {
    const float yy = y - 16.0f;
    const float uu = u - 128.0f;
    const float vv = v - 128.0f;
    out[0] = clampU8(1.1644f * yy + 1.7927f * vv);
    out[1] = clampU8(1.1644f * yy - 0.2132f * uu - 0.5329f * vv);
    out[2] = clampU8(1.1644f * yy + 2.1124f * uu);
    out[3] = 255;
}

// I420: Y plane w*h, then U plane w/2*h/2, then V plane w/2*h/2
static void i420ToRgba(const NDIlib_video_frame_v2_t& v, uint8_t* dst) {
    const int w = v.xres;
    const int h = v.yres;
    const uint8_t* yPlane = v.p_data;
    const uint8_t* uPlane = yPlane + (size_t)w * h;
    const uint8_t* vPlane = uPlane + (size_t)(w/2) * (h/2);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t yy = yPlane[y * w + x];
            const uint8_t uu = uPlane[(y/2) * (w/2) + (x/2)];
            const uint8_t vv = vPlane[(y/2) * (w/2) + (x/2)];
            yuvToRgb((float)yy, (float)uu, (float)vv, dst + ((size_t)y * w + x) * 4);
        }
    }
}

// YV12: Y, then V, then U (order swapped vs I420)
static void yv12ToRgba(const NDIlib_video_frame_v2_t& v, uint8_t* dst) {
    const int w = v.xres;
    const int h = v.yres;
    const uint8_t* yPlane = v.p_data;
    const uint8_t* vPlane = yPlane + (size_t)w * h;
    const uint8_t* uPlane = vPlane + (size_t)(w/2) * (h/2);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t yy = yPlane[y * w + x];
            const uint8_t uu = uPlane[(y/2) * (w/2) + (x/2)];
            const uint8_t vv = vPlane[(y/2) * (w/2) + (x/2)];
            yuvToRgb((float)yy, (float)uu, (float)vv, dst + ((size_t)y * w + x) * 4);
        }
    }
}

// NV12: Y plane w*h, then interleaved UV plane w*h/2 (UV interleaved)
static void nv12ToRgba(const NDIlib_video_frame_v2_t& v, uint8_t* dst) {
    const int w = v.xres;
    const int h = v.yres;
    const uint8_t* yPlane = v.p_data;
    const uint8_t* uvPlane = yPlane + (size_t)w * h;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t yy = yPlane[y * w + x];
            const int uvIndex = (y/2) * w + (x/2) * 2;
            const uint8_t uu = uvPlane[uvIndex + 0];
            const uint8_t vv = uvPlane[uvIndex + 1];
            yuvToRgb((float)yy, (float)uu, (float)vv, dst + ((size_t)y * w + x) * 4);
        }
    }
}

// ---------------------------------------------------------------- receiver object

struct VideoOut {
    std::vector<uint8_t> data[2];
    jobject bb[2] = {nullptr, nullptr};
    size_t cap = 0;
    int idx = 0;
};

struct AudioRing {
    std::vector<float> buf;
    size_t cap = 0;
    std::atomic<size_t> w{0}, r{0}; // element counts

    void reset(size_t channels, int rate) {
        cap = (size_t)rate * channels; // ~1 s
        buf.assign(cap * 2, 0.f);
        w = 0; r = 0;
    }

    void push(const float* v, size_t n) {
        const size_t c = cap;
        for (size_t i = 0; i < n; ++i) {
            size_t W = w.load(std::memory_order_relaxed);
            size_t R = r.load(std::memory_order_acquire);
            if (W - R >= c) { // full: drop oldest
                r.store(R + 1, std::memory_order_release);
            }
            buf[W % (c * 2)] = v[i];
            w.store(W + 1, std::memory_order_release);
        }
    }

    size_t pop(float* out, size_t n) {
        size_t got = 0;
        while (got < n) {
            size_t W = w.load(std::memory_order_acquire);
            size_t R = r.load(std::memory_order_relaxed);
            if (R >= W) break;
            out[got++] = buf[R % (cap * 2)];
            r.store(R + 1, std::memory_order_release);
        }
        return got;
    }
};

struct Receiver {
    NDIlib_recv_instance_t recv = nullptr;
    VideoOut vout;
    std::atomic<bool> audioRunning{false};
    std::atomic<bool> audioOn{false};
    std::atomic<bool> muted{false};
    std::thread audioThread;
    std::mutex streamMtx;
    AAudioStream* outStream = nullptr;
    int audioRate = 0;
    int audioCh = 0;
    AudioRing ring;
};

// ---------------------------------------------------------------- audio

static aaudio_data_callback_result_t audioCb(AAudioStream* stream, void* ud, void* audioData,
                                             int32_t numFrames) {
    auto* R = (Receiver*)ud;
    auto* out = (float*)audioData;
    const int ch = AAudioStream_getChannelCount(stream);
    const size_t need = (size_t)numFrames * (size_t)ch;
    if (R->muted.load(std::memory_order_relaxed) || !R->audioOn.load(std::memory_order_relaxed)) {
        memset(out, 0, need * sizeof(float));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    const size_t got = R->ring.pop(out, need);
    if (got < need)             memset(out + got, 0, (need - got) * sizeof(float));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void audioErrCb(AAudioStream* stream, void* ud, aaudio_result_t error) {
    (void)stream;
    (void)ud;
    (void)error;
    LOGW("AAudio error: %d", (int)error);
}

static void destroyStream(Receiver* R) {
    if (R->outStream) {
        AAudioStream_requestStop(R->outStream);
        AAudioStream_close(R->outStream);
        R->outStream = nullptr;
    }
}

static void ensureStream(Receiver* R, int rate, int ch) {
    std::lock_guard<std::mutex> lk(R->streamMtx);
    if (R->outStream && R->audioRate == rate && R->audioCh == ch) return;
    destroyStream(R);
    R->audioRate = rate;
    R->audioCh = ch;

    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) { LOGE("AAudio builder failed"); return; }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, ch);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setDataCallback(b, audioCb, R);
    AAudioStreamBuilder_setErrorCallback(b, audioErrCb, R);

    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &R->outStream);
    if (res == AAUDIO_OK && R->outStream) {
        AAudioStream_setBufferSizeInFrames(R->outStream,
                                           AAudioStream_getFramesPerBurst(R->outStream) * 4);
        res = AAudioStream_requestStart(R->outStream);
    }
    AAudioStreamBuilder_delete(b);

    if (res != AAUDIO_OK) {
        LOGE("AAudio open failed: %d", (int)res);
        if (R->outStream) { AAudioStream_close(R->outStream); R->outStream = nullptr; }
        R->audioOn = false;
        return;
    }
    R->ring.reset((size_t)ch, rate);
    R->audioOn = true;
    LOGI("AAudio started: %d Hz, %d ch", rate, ch);
}

static void audioLoop(Receiver* R) {
    while (R->audioRunning.load(std::memory_order_relaxed)) {
        NDIlib_audio_frame_v2_t a;
        memset(&a, 0, sizeof a);
        NDIlib_frame_type_e t = NDIlib_recv_capture_v2(R->recv, nullptr, &a, nullptr, 100);
        if (t == NDIlib_frame_type_audio) {
            const int ch = a.no_channels <= 1 ? 1 : 2;
            ensureStream(R, a.sample_rate, ch);
            if (R->audioOn.load(std::memory_order_relaxed)) {
                std::vector<float> tmp;
                tmp.reserve((size_t)a.no_samples * (size_t)ch);
                const uint8_t* base = (const uint8_t*)a.p_data;
                if (a.no_channels <= 1) {
                    const float* p0 = (const float*)base;
                    for (int s = 0; s < a.no_samples; ++s) tmp.push_back(p0[s]);
                } else if (a.no_channels == 2) {
                    const float* p0 = (const float*)base;
                    const float* p1 = (const float*)(base + a.channel_stride_in_bytes);
                    for (int s = 0; s < a.no_samples; ++s) {
                        tmp.push_back(p0[s]);
                        tmp.push_back(p1[s]);
                    }
                } else {
                    // downmix N>2 channels to stereo (even-index -> L, odd-index -> R)
                    std::vector<const float*> planes((size_t)a.no_channels);
                    for (int c = 0; c < a.no_channels; ++c)
                        planes[(size_t)c] = (const float*)(base + (size_t)c * a.channel_stride_in_bytes);
                    const int nl = (a.no_channels + 1) / 2;
                    const int nr = a.no_channels - nl;
                    for (int s = 0; s < a.no_samples; ++s) {
                        float l = 0.f, r = 0.f;
                        for (int c = 0; c < nl; ++c) l += planes[(size_t)c][s];
                        for (int c = 0; c < nr; ++c) r += planes[(size_t)(nl + c)][s];
                        tmp.push_back(l / (float)nl);
                        tmp.push_back(r / (float)nr);
                    }
                }
                R->ring.push(tmp.data(), tmp.size());
            }
            NDIlib_recv_free_audio_v2(R->recv, &a);
        }
    }
    std::lock_guard<std::mutex> lk(R->streamMtx);
    destroyStream(R);
}

// ---------------------------------------------------------------- JNI: NdiNative

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lekozaur_ndiviewer_NdiNative_initialize(JNIEnv*, jobject) {
    if (!NDIlib_is_supported_CPU()) { LOGE("CPU not supported by NDI"); return JNI_FALSE; }
    return NDIlib_initialize() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lekozaur_ndiviewer_NdiNative_version(JNIEnv* env, jobject) {
    return toJString(env, NDIlib_version());
}

// ---------------------------------------------------------------- JNI: NdiFinderJni

extern "C" JNIEXPORT jlong JNICALL
Java_com_lekozaur_ndiviewer_NdiFinderJni_nativeCreate(JNIEnv* env, jobject, jboolean showLocal,
                                                      jstring extraIps) {
    NDIlib_find_create_t cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.show_local_sources = showLocal == JNI_TRUE;
    const char* ips = extraIps ? env->GetStringUTFChars(extraIps, nullptr) : nullptr;
    cfg.p_extra_ips = (ips && ips[0]) ? ips : nullptr;
    NDIlib_initialize();
    NDIlib_find_instance_t f = NDIlib_find_create_v2(&cfg);
    if (ips) env->ReleaseStringUTFChars(extraIps, ips);
    if (!f) LOGE("find_create failed");
    return (jlong)(intptr_t)f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lekozaur_ndiviewer_NdiFinderJni_nativeDestroy(JNIEnv*, jobject, jlong h) {
    if (h) NDIlib_find_destroy((NDIlib_find_instance_t)(intptr_t)h);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lekozaur_ndiviewer_NdiFinderJni_nativeWaitForSources(JNIEnv*, jobject, jlong h,
                                                              jint timeoutMs) {
    if (!h) return JNI_FALSE;
    return NDIlib_find_wait_for_sources((NDIlib_find_instance_t)(intptr_t)h, (uint32_t)timeoutMs)
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lekozaur_ndiviewer_NdiFinderJni_nativeGetSources(JNIEnv* env, jobject, jlong h) {
    auto* f = (NDIlib_find_instance_t)(intptr_t)h;
    uint32_t n = 0;
    const NDIlib_source_t* srcs = f ? NDIlib_find_get_current_sources(f, &n) : nullptr;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)n * 2, strCls, nullptr);
    for (uint32_t i = 0; i < n; ++i) {
        jstring name = toJString(env, srcs[i].p_ndi_name);
        jstring url = toJString(env, srcs[i].p_url_address);
        env->SetObjectArrayElement(arr, (jsize)(i * 2), name);
        env->SetObjectArrayElement(arr, (jsize)(i * 2 + 1), url);
        if (name) env->DeleteLocalRef(name);
        if (url) env->DeleteLocalRef(url);
    }
    return arr;
}

// ---------------------------------------------------------------- JNI: NdiReceiverJni

struct FrameHolderIds {
    jclass cls = nullptr;
    jfieldID frameType, xres, yres, fourcc, fpsNum, fpsDen, aspect, timestamp;
    bool ok = false;
};
struct StatsHolderIds {
    jclass cls = nullptr;
    jfieldID total, dropped, connections;
    bool ok = false;
};
static FrameHolderIds gFid;
static StatsHolderIds gSid;

static bool initFrameIds(JNIEnv* env) {
    if (gFid.ok) return true;
    jclass c = env->FindClass("com/lekozaur/ndiviewer/NdiVideoFrame");
    if (!c || env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    gFid.cls = (jclass)env->NewGlobalRef(c);
    env->DeleteLocalRef(c);
    gFid.frameType = env->GetFieldID(gFid.cls, "frameType", "I");
    gFid.xres = env->GetFieldID(gFid.cls, "xres", "I");
    gFid.yres = env->GetFieldID(gFid.cls, "yres", "I");
    gFid.fourcc = env->GetFieldID(gFid.cls, "fourcc", "I");
    gFid.fpsNum = env->GetFieldID(gFid.cls, "fpsNum", "I");
    gFid.fpsDen = env->GetFieldID(gFid.cls, "fpsDen", "I");
    gFid.aspect = env->GetFieldID(gFid.cls, "aspect", "F");
    gFid.timestamp = env->GetFieldID(gFid.cls, "timestamp", "J");
    gFid.ok = !env->ExceptionCheck();
    env->ExceptionClear();
    return gFid.ok;
}

static bool initStatsIds(JNIEnv* env) {
    if (gSid.ok) return true;
    jclass c = env->FindClass("com/lekozaur/ndiviewer/NdiStats");
    if (!c || env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    gSid.cls = (jclass)env->NewGlobalRef(c);
    env->DeleteLocalRef(c);
    gSid.total = env->GetFieldID(gSid.cls, "totalFrames", "J");
    gSid.dropped = env->GetFieldID(gSid.cls, "droppedFrames", "J");
    gSid.connections = env->GetFieldID(gSid.cls, "connections", "I");
    gSid.ok = !env->ExceptionCheck();
    env->ExceptionClear();
    return gSid.ok;
}

static void releaseHolderIds(JNIEnv* env) {
    if (gFid.cls) { env->DeleteGlobalRef(gFid.cls); gFid = {}; }
    if (gSid.cls) { env->DeleteGlobalRef(gSid.cls); gSid = {}; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeCreate(JNIEnv* env, jobject, jstring recvName,
                                                        jint bandwidth) {
    NDIlib_initialize();

    NDIlib_recv_create_v3_t cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.color_format = NDIlib_recv_color_format_RGBX_RGBA;
    cfg.bandwidth = (NDIlib_recv_bandwidth_e)bandwidth;
    cfg.allow_video_fields = false;
    const char* nm = recvName ? env->GetStringUTFChars(recvName, nullptr) : nullptr;
    cfg.p_ndi_recv_name = (nm && nm[0]) ? nm : "NDI monitor (Android)";

    auto* R = new Receiver();
    R->recv = NDIlib_recv_create_v3(&cfg);
    if (nm) env->ReleaseStringUTFChars(recvName, nm);

    if (!R->recv) {
        LOGE("recv_create failed");
        delete R;
        return 0;
    }
    R->audioRunning = true;
    R->audioThread = std::thread(audioLoop, R);
    return (jlong)(intptr_t)R;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeConnect(JNIEnv* env, jobject, jlong h,
                                                         jstring url, jstring name) {
    auto* R = (Receiver*)(intptr_t)h;
    if (!R || !R->recv) return;
    NDIlib_source_t src;
    memset(&src, 0, sizeof src);
    const char* u = url ? env->GetStringUTFChars(url, nullptr) : nullptr;
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    src.p_ndi_name = n;
    src.p_url_address = u;
    NDIlib_recv_connect(R->recv, &src);
    if (u) env->ReleaseStringUTFChars(url, u);
    if (n) env->ReleaseStringUTFChars(name, n);
    LOGI("connect requested: %s", u ? u : "(null)");
}

extern "C" JNIEXPORT void JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeDisconnect(JNIEnv*, jobject, jlong h) {
    auto* R = (Receiver*)(intptr_t)h;
    if (!R || !R->recv) return;
    NDIlib_recv_connect(R->recv, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeSetMuted(JNIEnv*, jobject, jlong h,
                                                          jboolean muted) {
    auto* R = (Receiver*)(intptr_t)h;
    if (R) R->muted = muted == JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeCapture(JNIEnv* env, jobject, jlong h,
                                                         jint timeoutMs, jobject holder) {
    auto* R = (Receiver*)(intptr_t)h;
    if (!R || !R->recv || !initFrameIds(env)) return nullptr;

    NDIlib_video_frame_v2_t v;
    memset(&v, 0, sizeof v);
    NDIlib_frame_type_e t = NDIlib_recv_capture_v2(R->recv, &v, nullptr, nullptr,
                                                   (uint32_t)timeoutMs);

    if (t != NDIlib_frame_type_video) {
        if (t == NDIlib_frame_type_error) LOGE("recv error!");
        else if (t == NDIlib_frame_type_none) { /* no frame yet */ }
        env->SetIntField(holder, gFid.frameType, (jint)t);
        return nullptr;
    }

    if (!initFrameIds(env)) return nullptr;

    LOGI("video frame: %dx%d fourcc=%d fps=%d/%d stride=%d",
         v.xres, v.yres, (int)v.FourCC, v.frame_rate_N, v.frame_rate_D, v.line_stride_in_bytes);

    const size_t needed = (size_t)v.xres * (size_t)v.yres * 4;
    if (R->vout.cap != needed) {
        for (int i = 0; i < 2; ++i) {
            if (R->vout.bb[i]) { env->DeleteGlobalRef(R->vout.bb[i]); R->vout.bb[i] = nullptr; }
            R->vout.data[i].assign(needed, 0);
            jobject l = env->NewDirectByteBuffer(R->vout.data[i].data(), (jlong)needed);
            R->vout.bb[i] = env->NewGlobalRef(l);
            env->DeleteLocalRef(l);
        }
        R->vout.cap = needed;
        R->vout.idx = 0;
        LOGI("video buffer: %dx%d", v.xres, v.yres);
    }

    uint8_t* dst = R->vout.data[R->vout.idx].data();
    // Helper to print FourCC as chars for logging
    auto fourccStr = [](int f) -> std::string {
        char c[5] = {(char)(f & 0xFF), (char)((f>>8)&0xFF), (char)((f>>16)&0xFF), (char)((f>>24)&0xFF), 0};
        for (int i=0;i<4;i++) if (c[i] < 32 || c[i] > 126) c[i]='?';
        return std::string(c);
    };
    bool handled = true;
    switch (v.FourCC) {
        case NDIlib_FourCC_video_type_RGBA:
        case NDIlib_FourCC_video_type_RGBX:
            copyRgba(v, dst);
            break;
        case NDIlib_FourCC_video_type_BGRA:
        case NDIlib_FourCC_video_type_BGRX:
            swizzleBgra(v, dst);
            break;
        case NDIlib_FourCC_video_type_UYVY:
            uyvyToRgba(v, dst);
            break;
        case NDIlib_FourCC_video_type_I420:
            i420ToRgba(v, dst);
            break;
        case NDIlib_FourCC_video_type_YV12:
            yv12ToRgba(v, dst);
            break;
        case NDIlib_FourCC_video_type_NV12:
            nv12ToRgba(v, dst);
            break;
        case NDIlib_FourCC_video_type_UYVA:
            // UYVA = UYVY + trailing alpha plane - just render YUV part
            uyvyToRgba(v, dst);
            break;
        default:
            handled = false;
            break;
    }
    if (!handled) {
        // Check if it's a known compressed FourCC (HX/SpeedHQ)
        std::string fc = fourccStr((int)v.FourCC);
        LOGW("unsupported FourCC: %d (%s) - likely compressed HX/SpeedHQ", (int)v.FourCC, fc.c_str());
        // Store error info in holder so Java can show user-friendly message
        env->SetIntField(holder, gFid.frameType, (jint)NDIlib_frame_type_error);
        env->SetIntField(holder, gFid.fourcc, (jint)v.FourCC);
        env->SetIntField(holder, gFid.xres, v.xres);
        env->SetIntField(holder, gFid.yres, v.yres);
        NDIlib_recv_free_video_v2(R->recv, &v);
        return nullptr;
    }

    const jobject out = R->vout.bb[R->vout.idx];
    R->vout.idx ^= 1;

    env->SetIntField(holder, gFid.frameType, (jint)NDIlib_frame_type_video);
    env->SetIntField(holder, gFid.xres, v.xres);
    env->SetIntField(holder, gFid.yres, v.yres);
    env->SetIntField(holder, gFid.fourcc, (jint)v.FourCC);
    env->SetIntField(holder, gFid.fpsNum, v.frame_rate_N);
    env->SetIntField(holder, gFid.fpsDen, v.frame_rate_D);
    env->SetFloatField(holder, gFid.aspect, v.picture_aspect_ratio);
    env->SetLongField(holder, gFid.timestamp, v.timestamp);

    NDIlib_recv_free_video_v2(R->recv, &v);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeGetStats(JNIEnv* env, jobject, jlong h,
                                                          jobject stats) {
    auto* R = (Receiver*)(intptr_t)h;
    if (!R || !R->recv || !initStatsIds(env)) return;
    NDIlib_recv_performance_t total, dropped;
    memset(&total, 0, sizeof total);
    memset(&dropped, 0, sizeof dropped);
    NDIlib_recv_get_performance(R->recv, &total, &dropped);
    env->SetLongField(stats, gSid.total, total.video_frames);
    env->SetLongField(stats, gSid.dropped, dropped.video_frames);
    env->SetIntField(stats, gSid.connections, NDIlib_recv_get_no_connections(R->recv));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lekozaur_ndiviewer_NdiReceiverJni_nativeDestroy(JNIEnv* env, jobject, jlong h) {
    auto* R = (Receiver*)(intptr_t)h;
    if (!R) return;
    R->audioRunning = false;
    if (R->audioThread.joinable()) R->audioThread.join();
    if (R->recv) NDIlib_recv_destroy(R->recv);
    for (int i = 0; i < 2; ++i)
        if (R->vout.bb[i]) { env->DeleteGlobalRef(R->vout.bb[i]); R->vout.bb[i] = nullptr; }
    delete R;
}

// ---------------------------------------------------------------- JNI_OnLoad / unload

jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    LOGI("ndiviewer native loaded, NDI %s", NDIlib_version());
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) releaseHolderIds(env);
    NDIlib_destroy();
}
