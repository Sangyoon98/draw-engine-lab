// Clean-room native drawing engine (C++ + GLES2), mirroring the Samsung-style
// "Java surface → JNI → native stroke engine → GL shader render" architecture.
// Our own code; nothing is copied from Samsung's libraries.
//
// Pens: fountain / ballpoint / marker / highlighter / pencil / brush + eraser.
// Each stroke is tessellated into a variable-width triangle strip and drawn with a
// solid shader; per-pen width dynamics (pressure, speed, taper) + color.
//
// Text follows Samsung's object model too: a text box is a native object (text + rich-text
// span list) living in the same z-ordered object list as strokes. Like Samsung's
// ObjectTextDrawing (Skia canvas → bitmap → GL), each box is rasterized into a bitmap by the
// platform text stack (TextRaster.kt, called back over JNI) and uploaded as a GL texture,
// then re-rasterized when the zoom level changes so it stays sharp (cf. updateBitmap(scale)).

#include <jni.h>
#include <GLES2/gl2.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "nativegl", __VA_ARGS__)

namespace {

struct Pt { float x, y, p; };
struct Stroke {
    int kind = 0;
    float r = 0, g = 0, b = 0, a = 1;
    float base = 9.f;
    std::vector<Pt> pts;
    // Retained GL geometry (Samsung-style dry cache): committed strokes are
    // tessellated once and kept in their own GL buffer, so onDrawFrame no longer
    // re-smooths/re-uploads every stroke each frame. Verts are world-space; the
    // vertex shader applies u_scale/u_off, so zoom stays sharp (vectors, not raster).
    GLuint vbo = 0;
    GLsizei vcount = 0;
    bool dirty = true;
    long seq = 0; // position in the shared stroke/text object order (draw + undo)
};

// Native text object (cf. Samsung ObjectText: text + RichTextSpan list, flat [type,start,end,value]*).
struct TextObj {
    int id = 0;
    long seq = 0;
    float x = 0, y = 0, w = 0, h = 0; // world rect; h comes from the Java layout
    std::u16string text;
    std::vector<jint> spans;
    GLuint tex = 0;
    float texScale = 0.f;  // raster scale the texture was made at
    float qw = 0, qh = 0;  // quad size in world units (bitmap size / texScale)
    bool dirty = true;
};

// pen kinds (must match Kotlin GlPen ordinals)
enum { FOUNTAIN = 0, BALLPOINT, MARKER, HIGHLIGHTER, PENCIL, BRUSH, ERASER_OBJECT, ERASER_PIXEL };

std::vector<Stroke> g_strokes;
Stroke* g_current = nullptr;

int g_pen = FOUNTAIN;
float g_r = 0, g_g = 0, g_b = 0, g_a = 1;
float g_base = 9.f;

float g_vpW = 1.f, g_vpH = 1.f;
float g_scale = 1.f, g_offX = 0.f, g_offY = 0.f; // view pan/zoom (world → screen)
GLuint g_prog = 0, g_vbo = 0;
GLint g_aPos = -1, g_uVp = -1, g_uColor = -1, g_uScale = -1, g_uOff = -1;

long g_seq = 0;
// Text model is touched from the UI thread (hit test / edit) and the GL thread (draw, undo),
// so it is guarded; GL textures are only created/deleted on the GL thread (g_deadTex).
std::mutex g_textMu;
std::vector<TextObj> g_texts;
std::vector<GLuint> g_deadTex;
int g_nextTextId = 1;
int g_hiddenText = -1; // box currently open in the Java editor; not drawn natively

jclass g_rasterCls = nullptr;
jmethodID g_rasterFn = nullptr;
GLuint g_texProg = 0;
GLint g_tPos = -1, g_tUv = -1, g_tVp = -1, g_tScale = -1, g_tOff = -1, g_tTex = -1;

const char* kTexVert =
    "attribute vec2 a_pos;\n"
    "attribute vec2 a_uv;\n"
    "uniform vec2 u_vp;\n"
    "uniform float u_scale;\n"
    "uniform vec2 u_off;\n"
    "varying vec2 v_uv;\n"
    "void main() {\n"
    "  vec2 p = a_pos * u_scale + u_off;\n"
    "  v_uv = a_uv;\n"
    "  gl_Position = vec4(p.x / u_vp.x * 2.0 - 1.0, 1.0 - p.y / u_vp.y * 2.0, 0.0, 1.0);\n"
    "}\n";

const char* kTexFrag =
    "precision mediump float;\n"
    "uniform sampler2D u_tex;\n"
    "varying vec2 v_uv;\n"
    "void main() { gl_FragColor = texture2D(u_tex, v_uv); }\n";

const char* kVert =
    "attribute vec2 a_pos;\n"
    "uniform vec2 u_vp;\n"
    "uniform float u_scale;\n"
    "uniform vec2 u_off;\n"
    "void main() {\n"
    "  vec2 p = a_pos * u_scale + u_off;\n"
    "  gl_Position = vec4(p.x / u_vp.x * 2.0 - 1.0,\n"
    "                     1.0 - p.y / u_vp.y * 2.0, 0.0, 1.0);\n"
    "}\n";

const char* kFrag =
    "precision mediump float;\n"
    "uniform vec4 u_color;\n"
    "void main() { gl_FragColor = u_color; }\n";

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(s, sizeof(log), nullptr, log); LOGE("shader: %s", log); }
    return s;
}

void buildProgram() {
    GLuint v = compile(GL_VERTEX_SHADER, kVert);
    GLuint f = compile(GL_FRAGMENT_SHADER, kFrag);
    g_prog = glCreateProgram();
    glAttachShader(g_prog, v);
    glAttachShader(g_prog, f);
    glLinkProgram(g_prog);
    glDeleteShader(v);
    glDeleteShader(f);
    g_aPos = glGetAttribLocation(g_prog, "a_pos");
    g_uVp = glGetUniformLocation(g_prog, "u_vp");
    g_uColor = glGetUniformLocation(g_prog, "u_color");
    g_uScale = glGetUniformLocation(g_prog, "u_scale");
    g_uOff = glGetUniformLocation(g_prog, "u_off");
    glGenBuffers(1, &g_vbo);

    GLuint tv = compile(GL_VERTEX_SHADER, kTexVert);
    GLuint tf = compile(GL_FRAGMENT_SHADER, kTexFrag);
    g_texProg = glCreateProgram();
    glAttachShader(g_texProg, tv);
    glAttachShader(g_texProg, tf);
    glLinkProgram(g_texProg);
    glDeleteShader(tv);
    glDeleteShader(tf);
    g_tPos = glGetAttribLocation(g_texProg, "a_pos");
    g_tUv = glGetAttribLocation(g_texProg, "a_uv");
    g_tVp = glGetUniformLocation(g_texProg, "u_vp");
    g_tScale = glGetUniformLocation(g_texProg, "u_scale");
    g_tOff = glGetUniformLocation(g_texProg, "u_off");
    g_tTex = glGetUniformLocation(g_texProg, "u_tex");
}

float crf(float a, float b, float c, float d, float t) {
    float t2 = t * t, t3 = t2 * t;
    return 0.5f * ((2.f * b) + (-a + c) * t + (2.f * a - 5.f * b + 4.f * c - d) * t2 +
                   (-a + 3.f * b - 3.f * c + d) * t3);
}

std::vector<Pt> smoothStroke(const std::vector<Pt>& s) {
    size_t n = s.size();
    if (n < 3) return s;
    std::vector<Pt> out;
    out.reserve(n * 4);
    for (size_t i = 0; i + 1 < n; ++i) {
        const Pt& p0 = s[i > 0 ? i - 1 : 0];
        const Pt& p1 = s[i];
        const Pt& p2 = s[i + 1];
        const Pt& p3 = s[i + 2 < n ? i + 2 : n - 1];
        float dx = p2.x - p1.x, dy = p2.y - p1.y;
        int steps = (int)(std::sqrt(dx * dx + dy * dy) / 5.f);
        if (steps < 1) steps = 1;
        if (steps > 8) steps = 8;
        for (int k = 0; k < steps; ++k) {
            float t = (float)k / steps;
            out.push_back({crf(p0.x, p1.x, p2.x, p3.x, t),
                           crf(p0.y, p1.y, p2.y, p3.y, t),
                           p1.p + (p2.p - p1.p) * t});
        }
    }
    out.push_back(s.back());
    return out;
}

struct Cfg { float scale, minMul, maxMul, taper; bool speed; };

Cfg cfgFor(int k) {
    switch (k) {
        case FOUNTAIN:    return {1.0f, 0.20f, 1.25f, 18.f, true};
        case BALLPOINT:   return {1.0f, 0.80f, 1.05f, 4.f, false};
        case MARKER:      return {2.3f, 0.95f, 1.00f, 2.f, false};
        case HIGHLIGHTER: return {3.5f, 1.00f, 1.00f, 2.f, false};
        case PENCIL:      return {0.9f, 0.45f, 1.00f, 6.f, false};
        case BRUSH:       return {1.0f, 0.12f, 1.90f, 26.f, true};
        case ERASER_PIXEL:return {2.5f, 1.00f, 1.00f, 0.f, false}; // wide opaque white
        default:          return {1.0f, 0.5f, 1.0f, 0.f, false};
    }
}

std::vector<float> halfWidths(const std::vector<Pt>& sm, int kind, float base) {
    size_t n = sm.size();
    std::vector<float> w(n, 0.f);
    if (n == 0) return w;
    Cfg c = cfgFor(kind);
    for (size_t i = 0; i < n; ++i) {
        float pr = sm[i].p;
        if (pr <= 0.f || pr > 1.f) pr = 0.6f;
        float mul = c.minMul + (c.maxMul - c.minMul) * pr;
        if (c.speed && i > 0) {
            float dx = sm[i].x - sm[i - 1].x, dy = sm[i].y - sm[i - 1].y;
            mul *= 1.f - std::min(0.6f, std::sqrt(dx * dx + dy * dy) / 45.f);
        }
        w[i] = base * c.scale * mul;
    }
    std::vector<float> sw(n);
    for (size_t i = 0; i < n; ++i) {
        float sum = 0.f; int cnt = 0;
        for (long k = (long)i - 2; k <= (long)i + 2; ++k) {
            if (k >= 0 && k < (long)n) { sum += w[k]; ++cnt; }
        }
        sw[i] = sum / cnt;
    }
    if (c.taper > 0.f && n > 1) {
        std::vector<float> fs(n, 0.f), fe(n, 0.f);
        for (size_t i = 1; i < n; ++i) {
            float dx = sm[i].x - sm[i - 1].x, dy = sm[i].y - sm[i - 1].y;
            fs[i] = fs[i - 1] + std::sqrt(dx * dx + dy * dy);
        }
        for (long i = (long)n - 2; i >= 0; --i) {
            float dx = sm[i + 1].x - sm[i].x, dy = sm[i + 1].y - sm[i].y;
            fe[i] = fe[i + 1] + std::sqrt(dx * dx + dy * dy);
        }
        for (size_t i = 0; i < n; ++i) {
            float f = std::min(fs[i], fe[i]) / c.taper;
            if (f < 0.15f) f = 0.15f;
            if (f > 1.f) f = 1.f;
            sw[i] *= f;
        }
    }
    for (size_t i = 0; i < n; ++i) sw[i] *= 0.5f;
    return sw;
}

void deleteStrokeGL(Stroke& st) {
    if (st.vbo) { glDeleteBuffers(1, &st.vbo); st.vbo = 0; }
    st.vcount = 0;
}

// (Re)tessellate a stroke into its own GL buffer. Runs once per committed stroke
// (and every frame only for the in-progress one, which is marked dirty on move).
void ensureCache(Stroke& st) {
    if (!st.dirty && st.vbo) return;
    st.dirty = false;
    std::vector<Pt> s = smoothStroke(st.pts);
    size_t n = s.size();
    if (n < 2) { st.vcount = 0; return; }
    std::vector<float> hw = halfWidths(s, st.kind, st.base);

    // per-point normal (perp to the local tangent)
    std::vector<float> nx(n), ny(n);
    for (size_t i = 0; i < n; ++i) {
        const Pt& a = s[i > 0 ? i - 1 : i];
        const Pt& b = s[i < n - 1 ? i + 1 : i];
        float dx = b.x - a.x, dy = b.y - a.y;
        float len = std::sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) { dx = 1.f; dy = 0.f; } else { dx /= len; dy /= len; }
        nx[i] = -dy; ny[i] = dx;
    }

    // one independent quad per segment (GL_TRIANGLES) — never bowties like a long strip
    std::vector<float> v;
    v.reserve((n - 1) * 12);
    for (size_t i = 0; i + 1 < n; ++i) {
        float aLx = s[i].x - nx[i] * hw[i], aLy = s[i].y - ny[i] * hw[i];
        float aRx = s[i].x + nx[i] * hw[i], aRy = s[i].y + ny[i] * hw[i];
        float bLx = s[i + 1].x - nx[i + 1] * hw[i + 1], bLy = s[i + 1].y - ny[i + 1] * hw[i + 1];
        float bRx = s[i + 1].x + nx[i + 1] * hw[i + 1], bRy = s[i + 1].y + ny[i + 1] * hw[i + 1];
        v.insert(v.end(), {aLx, aLy, aRx, aRy, bLx, bLy, aRx, aRy, bRx, bRy, bLx, bLy});
    }
    if (!st.vbo) glGenBuffers(1, &st.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, st.vbo);
    // in-progress stroke re-uploads each move; committed strokes upload once.
    glBufferData(GL_ARRAY_BUFFER, v.size() * sizeof(float), v.data(),
                 (&st == g_current) ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW);
    st.vcount = (GLsizei)(v.size() / 2);
}

void drawStroke(Stroke& st) {
    ensureCache(st);
    if (st.vcount == 0) return;
    glBindBuffer(GL_ARRAY_BUFFER, st.vbo);
    glEnableVertexAttribArray(g_aPos);
    glVertexAttribPointer(g_aPos, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    GLsizei count = st.vcount;

    float a = st.a;
    if (st.kind == HIGHLIGHTER) a *= 0.30f;
    else if (st.kind == PENCIL) a *= 0.80f;

    if (a < 0.99f) {
        // translucent: stencil so every covered pixel is painted exactly once
        // (self-overlap no longer stacks alpha → no blotches / colour jumps)
        glEnable(GL_STENCIL_TEST);
        glClear(GL_STENCIL_BUFFER_BIT);
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glDrawArrays(GL_TRIANGLES, 0, count);

        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glStencilFunc(GL_EQUAL, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_ZERO); // paint once, then clear that pixel's stencil
        glUniform4f(g_uColor, st.r, st.g, st.b, a);
        glDrawArrays(GL_TRIANGLES, 0, count);
        glDisable(GL_STENCIL_TEST);
    } else {
        glUniform4f(g_uColor, st.r, st.g, st.b, a);
        glDrawArrays(GL_TRIANGLES, 0, count);
    }
}

// raster scale for the current zoom, in 0.5 steps so pinching doesn't re-raster every frame
float textRasterScale(const TextObj& t) {
    float s = std::ceil(g_scale * 2.f) / 2.f;
    s = std::max(1.f, std::min(4.f, s));
    while (s > 0.5f && (t.w * s > 4096.f || t.h * s > 4096.f)) s *= 0.5f; // GL max texture guard
    return s;
}

// Text → bitmap via the platform text stack (JNI callback), → GL texture.
void rasterizeText(JNIEnv* env, TextObj& t) {
    float s = textRasterScale(t);
    jstring js = env->NewString(reinterpret_cast<const jchar*>(t.text.data()), (jsize)t.text.size());
    jintArray ja = env->NewIntArray((jsize)t.spans.size());
    env->SetIntArrayRegion(ja, 0, (jsize)t.spans.size(), t.spans.data());
    jobject bmp = env->CallStaticObjectMethod(g_rasterCls, g_rasterFn, js, ja, (jint)t.w, s);
    env->DeleteLocalRef(js);
    env->DeleteLocalRef(ja);
    if (env->ExceptionCheck() || !bmp) { env->ExceptionClear(); LOGE("text raster failed"); t.dirty = false; return; }

    AndroidBitmapInfo info;
    void* px = nullptr;
    if (AndroidBitmap_getInfo(env, bmp, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 && info.stride == info.width * 4 &&
        AndroidBitmap_lockPixels(env, bmp, &px) == ANDROID_BITMAP_RESULT_SUCCESS) {
        if (!t.tex) glGenTextures(1, &t.tex);
        glBindTexture(GL_TEXTURE_2D, t.tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)info.width, (GLsizei)info.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, px);
        AndroidBitmap_unlockPixels(env, bmp);
        t.texScale = s;
        t.qw = info.width / s;
        t.qh = info.height / s;
    } else {
        LOGE("text bitmap lock failed");
    }
    env->DeleteLocalRef(bmp);
    t.dirty = false;
}

void drawText(JNIEnv* env, TextObj& t) {
    if (t.id == g_hiddenText || t.text.empty()) return;
    if (t.dirty || t.texScale != textRasterScale(t)) rasterizeText(env, t);
    if (!t.tex) return;
    glUseProgram(g_texProg);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, t.tex);
    glUniform1i(g_tTex, 0);
    const float v[] = {t.x, t.y, 0.f, 0.f, t.x + t.qw, t.y, 1.f, 0.f,
                       t.x, t.y + t.qh, 0.f, 1.f, t.x + t.qw, t.y + t.qh, 1.f, 1.f};
    glBindBuffer(GL_ARRAY_BUFFER, 0); // client-side quad
    glEnableVertexAttribArray(g_tPos);
    glEnableVertexAttribArray(g_tUv);
    glVertexAttribPointer(g_tPos, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), v);
    glVertexAttribPointer(g_tUv, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), v + 2);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); // Android bitmaps are premultiplied
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisableVertexAttribArray(g_tUv);
    glUseProgram(g_prog);
}

TextObj* findText(int id) {
    for (auto& t : g_texts) if (t.id == id) return &t;
    return nullptr;
}

void dropText(std::vector<TextObj>::iterator it) {
    if (it->tex) g_deadTex.push_back(it->tex);
    g_texts.erase(it);
}

std::u16string readString(JNIEnv* env, jstring s) {
    const jchar* c = env->GetStringChars(s, nullptr);
    std::u16string out(reinterpret_cast<const char16_t*>(c), env->GetStringLength(s));
    env->ReleaseStringChars(s, c);
    return out;
}

std::vector<jint> readInts(JNIEnv* env, jintArray a) {
    std::vector<jint> out(env->GetArrayLength(a));
    env->GetIntArrayRegion(a, 0, (jsize)out.size(), out.data());
    return out;
}

void eraseAt(float x, float y) {
    const float R = 28.f;
    for (auto it = g_strokes.begin(); it != g_strokes.end();) {
        bool hit = false;
        for (const Pt& pt : it->pts) {
            float dx = pt.x - x, dy = pt.y - y;
            if (dx * dx + dy * dy < R * R) { hit = true; break; }
        }
        if (hit) { deleteStrokeGL(*it); it = g_strokes.erase(it); } else ++it;
    }
}

} // namespace

extern "C" {

#define JNIF(name) JNIEXPORT void JNICALL Java_com_example_drawenginelab_nativegl_NativeGlBridge_##name
#define JNIR(ret, name) JNIEXPORT ret JNICALL Java_com_example_drawenginelab_nativegl_NativeGlBridge_##name

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/example/drawenginelab/nativegl/TextRaster");
    if (!cls) return JNI_ERR;
    g_rasterCls = (jclass)env->NewGlobalRef(cls);
    g_rasterFn = env->GetStaticMethodID(cls, "rasterize", "(Ljava/lang/String;[IIF)Landroid/graphics/Bitmap;");
    return JNI_VERSION_1_6;
}

JNIF(onSurfaceCreated)(JNIEnv*, jobject) {
    buildProgram();
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    // GL context recreated → old buffer names are invalid; drop them and re-cache.
    for (auto& s : g_strokes) { s.vbo = 0; s.dirty = true; }
    std::lock_guard<std::mutex> lock(g_textMu);
    for (auto& t : g_texts) { t.tex = 0; t.dirty = true; }
    g_deadTex.clear();
}

JNIF(onSurfaceChanged)(JNIEnv*, jobject, jint w, jint h) {
    g_vpW = (float)w; g_vpH = (float)h;
    glViewport(0, 0, w, h);
}

JNIF(onDrawFrame)(JNIEnv* env, jobject) {
    glClearColor(1.f, 1.f, 1.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (g_prog == 0) return;
    glUseProgram(g_prog);
    glUniform2f(g_uVp, g_vpW, g_vpH);
    glUniform1f(g_uScale, g_scale);
    glUniform2f(g_uOff, g_offX, g_offY);
    glUseProgram(g_texProg);
    glUniform2f(g_tVp, g_vpW, g_vpH);
    glUniform1f(g_tScale, g_scale);
    glUniform2f(g_tOff, g_offX, g_offY);
    glUseProgram(g_prog);

    std::lock_guard<std::mutex> lock(g_textMu);
    if (!g_deadTex.empty()) { glDeleteTextures((GLsizei)g_deadTex.size(), g_deadTex.data()); g_deadTex.clear(); }
    // Strokes and texts share one object order (seq), like Samsung's object list: merge-draw.
    // g_current is already inside g_strokes (pushed in down()), so the loop
    // draws the in-progress stroke too — drawing it again here would double-blend
    // translucent pens (highlighter looked darker while wet, lighter on release).
    size_t si = 0, ti = 0;
    while (si < g_strokes.size() || ti < g_texts.size()) {
        bool stroke = ti >= g_texts.size() || (si < g_strokes.size() && g_strokes[si].seq < g_texts[ti].seq);
        if (stroke) drawStroke(g_strokes[si++]); else drawText(env, g_texts[ti++]);
    }
}

JNIF(setPen)(JNIEnv*, jobject, jint pen) { g_pen = pen; }
JNIF(setColor)(JNIEnv*, jobject, jfloat r, jfloat g, jfloat b, jfloat a) { g_r = r; g_g = g; g_b = b; g_a = a; }
JNIF(setSize)(JNIEnv*, jobject, jfloat size) { g_base = size; }

JNIF(down)(JNIEnv*, jobject, jfloat x, jfloat y, jfloat p) {
    float wx = (x - g_offX) / g_scale, wy = (y - g_offY) / g_scale; // screen → world
    if (g_pen == ERASER_OBJECT) { eraseAt(wx, wy); return; }
    float r = g_r, g = g_g, b = g_b, a = g_a;
    if (g_pen == ERASER_PIXEL) { r = 1.f; g = 1.f; b = 1.f; a = 1.f; } // paint background = erase
    g_strokes.push_back({g_pen, r, g, b, a, g_base, {}});
    g_strokes.back().seq = ++g_seq;
    g_current = &g_strokes.back();
    g_current->pts.push_back({wx, wy, p});
}

JNIF(move)(JNIEnv*, jobject, jfloat x, jfloat y, jfloat p) {
    float wx = (x - g_offX) / g_scale, wy = (y - g_offY) / g_scale;
    if (g_pen == ERASER_OBJECT) { eraseAt(wx, wy); return; }
    if (g_current) { g_current->pts.push_back({wx, wy, p}); g_current->dirty = true; }
}

JNIF(setView)(JNIEnv*, jobject, jfloat scale, jfloat offX, jfloat offY) {
    g_scale = scale; g_offX = offX; g_offY = offY;
}

JNIF(resetView)(JNIEnv*, jobject) {
    g_scale = 1.f; g_offX = 0.f; g_offY = 0.f;
}

JNIF(up)(JNIEnv*, jobject) {
    if (g_current && g_current->pts.size() < 2) { deleteStrokeGL(g_strokes.back()); g_strokes.pop_back(); }
    g_current = nullptr;
}

// undo removes the most recently added object, stroke or text (one shared object order)
JNIF(undo)(JNIEnv*, jobject) {
    g_current = nullptr;
    std::lock_guard<std::mutex> lock(g_textMu);
    long ss = g_strokes.empty() ? -1 : g_strokes.back().seq;
    long ts = g_texts.empty() ? -1 : g_texts.back().seq;
    if (ss < 0 && ts < 0) return;
    if (ss > ts) { deleteStrokeGL(g_strokes.back()); g_strokes.pop_back(); }
    else dropText(g_texts.end() - 1);
}

JNIF(clearAll)(JNIEnv*, jobject) {
    for (auto& s : g_strokes) deleteStrokeGL(s);
    g_strokes.clear();
    g_current = nullptr;
    std::lock_guard<std::mutex> lock(g_textMu);
    while (!g_texts.empty()) dropText(g_texts.end() - 1);
}

// --- text objects (called from the UI thread; model guarded by g_textMu) ---------------

JNIR(jint, textAdd)(JNIEnv* env, jobject, jfloat x, jfloat y, jfloat w, jfloat h, jstring text, jintArray spans) {
    std::lock_guard<std::mutex> lock(g_textMu);
    TextObj t;
    t.id = g_nextTextId++;
    t.seq = ++g_seq;
    t.x = x; t.y = y; t.w = w; t.h = h;
    t.text = readString(env, text);
    t.spans = readInts(env, spans);
    g_texts.push_back(std::move(t));
    return g_texts.back().id;
}

JNIF(textUpdate)(JNIEnv* env, jobject, jint id, jfloat h, jstring text, jintArray spans) {
    std::lock_guard<std::mutex> lock(g_textMu);
    if (TextObj* t = findText(id)) { t->h = h; t->text = readString(env, text); t->spans = readInts(env, spans); t->dirty = true; }
}

JNIF(textMove)(JNIEnv*, jobject, jint id, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> lock(g_textMu);
    if (TextObj* t = findText(id)) { t->x = x; t->y = y; }
}

JNIF(textRemove)(JNIEnv*, jobject, jint id) {
    std::lock_guard<std::mutex> lock(g_textMu);
    for (auto it = g_texts.begin(); it != g_texts.end(); ++it) if (it->id == id) { dropText(it); return; }
}

JNIF(textSetHidden)(JNIEnv*, jobject, jint id) {
    std::lock_guard<std::mutex> lock(g_textMu);
    g_hiddenText = id;
}

// topmost text box under a world point, or -1
JNIR(jint, textHit)(JNIEnv*, jobject, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> lock(g_textMu);
    for (auto it = g_texts.rbegin(); it != g_texts.rend(); ++it) {
        float h = std::max(it->h, 40.f);
        if (x >= it->x && x <= it->x + it->w && y >= it->y && y <= it->y + h) return it->id;
    }
    return -1;
}

JNIR(jstring, textGet)(JNIEnv* env, jobject, jint id) {
    std::lock_guard<std::mutex> lock(g_textMu);
    TextObj* t = findText(id);
    if (!t) return env->NewStringUTF("");
    return env->NewString(reinterpret_cast<const jchar*>(t->text.data()), (jsize)t->text.size());
}

JNIR(jintArray, textSpans)(JNIEnv* env, jobject, jint id) {
    std::lock_guard<std::mutex> lock(g_textMu);
    TextObj* t = findText(id);
    jsize n = t ? (jsize)t->spans.size() : 0;
    jintArray a = env->NewIntArray(n);
    if (t) env->SetIntArrayRegion(a, 0, n, t->spans.data());
    return a;
}

// [x, y, w, h] in world units
JNIR(jfloatArray, textRect)(JNIEnv* env, jobject, jint id) {
    std::lock_guard<std::mutex> lock(g_textMu);
    TextObj* t = findText(id);
    float r[4] = {0, 0, 0, 0};
    if (t) { r[0] = t->x; r[1] = t->y; r[2] = t->w; r[3] = t->h; }
    jfloatArray a = env->NewFloatArray(4);
    env->SetFloatArrayRegion(a, 0, 4, r);
    return a;
}

} // extern "C"
