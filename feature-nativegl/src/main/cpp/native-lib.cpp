// Clean-room native drawing engine (C++ + GLES2), mirroring the Samsung-style
// "Java surface → JNI → native stroke engine → GL shader render" architecture.
// Our own code; nothing is copied from Samsung's libraries.
//
// Pens: fountain / ballpoint / marker / highlighter / pencil / brush + eraser.
// Each stroke is tessellated into a variable-width triangle strip and drawn with a
// solid shader; per-pen width dynamics (pressure, speed, taper) + color.

#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
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

void drawStroke(const Stroke& st) {
    std::vector<Pt> s = smoothStroke(st.pts);
    size_t n = s.size();
    if (n < 2) return;
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
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, v.size() * sizeof(float), v.data(), GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(g_aPos);
    glVertexAttribPointer(g_aPos, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    GLsizei count = (GLsizei)(v.size() / 2);

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

void eraseAt(float x, float y) {
    const float R = 28.f;
    for (auto it = g_strokes.begin(); it != g_strokes.end();) {
        bool hit = false;
        for (const Pt& pt : it->pts) {
            float dx = pt.x - x, dy = pt.y - y;
            if (dx * dx + dy * dy < R * R) { hit = true; break; }
        }
        if (hit) it = g_strokes.erase(it); else ++it;
    }
}

} // namespace

extern "C" {

#define JNIF(name) JNIEXPORT void JNICALL Java_com_example_drawenginelab_nativegl_NativeGlBridge_##name

JNIF(onSurfaceCreated)(JNIEnv*, jobject) {
    buildProgram();
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
}

JNIF(onSurfaceChanged)(JNIEnv*, jobject, jint w, jint h) {
    g_vpW = (float)w; g_vpH = (float)h;
    glViewport(0, 0, w, h);
}

JNIF(onDrawFrame)(JNIEnv*, jobject) {
    glClearColor(1.f, 1.f, 1.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (g_prog == 0) return;
    glUseProgram(g_prog);
    glUniform2f(g_uVp, g_vpW, g_vpH);
    glUniform1f(g_uScale, g_scale);
    glUniform2f(g_uOff, g_offX, g_offY);
    for (const auto& s : g_strokes) drawStroke(s);
    if (g_current) drawStroke(*g_current);
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
    g_current = &g_strokes.back();
    g_current->pts.push_back({wx, wy, p});
}

JNIF(move)(JNIEnv*, jobject, jfloat x, jfloat y, jfloat p) {
    float wx = (x - g_offX) / g_scale, wy = (y - g_offY) / g_scale;
    if (g_pen == ERASER_OBJECT) { eraseAt(wx, wy); return; }
    if (g_current) g_current->pts.push_back({wx, wy, p});
}

JNIF(setView)(JNIEnv*, jobject, jfloat scale, jfloat offX, jfloat offY) {
    g_scale = scale; g_offX = offX; g_offY = offY;
}

JNIF(resetView)(JNIEnv*, jobject) {
    g_scale = 1.f; g_offX = 0.f; g_offY = 0.f;
}

JNIF(up)(JNIEnv*, jobject) {
    if (g_current && g_current->pts.size() < 2) g_strokes.pop_back();
    g_current = nullptr;
}

JNIF(undo)(JNIEnv*, jobject) {
    g_current = nullptr;
    if (!g_strokes.empty()) g_strokes.pop_back();
}

JNIF(clearAll)(JNIEnv*, jobject) {
    g_strokes.clear();
    g_current = nullptr;
}

} // extern "C"
