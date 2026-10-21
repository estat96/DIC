// Быстрая 2D цифровая корреляция изображений (DIC) для Android.
// Этап 1: целочисленный поиск по ZNCC вокруг прогноза (смещение с прошлого кадра).
// Этап 2: субпиксельное уточнение IC-GN (inverse compositional Gauss-Newton)
//         с аффинной функцией формы (u, ux, uy, v, vx, vy), критерий ZNSSD.
// Опорный кадр фиксирован (Лагранжево описание), расчёт распараллелен по точкам сетки.
#include <jni.h>
#include <vector>
#include <thread>
#include <atomic>
#include <cmath>
#include <chrono>
#include <cstring>
#include <algorithm>

namespace {

struct Img {
    int w = 0, h = 0;
    std::vector<float> p;
    inline float at(int x, int y) const { return p[(size_t)y * w + x]; }
    inline float bilin(float x, float y) const {
        int x0 = (int)x, y0 = (int)y;
        float fx = x - x0, fy = y - y0;
        const float* r = &p[(size_t)y0 * w + x0];
        return (r[0] * (1 - fx) + r[1] * fx) * (1 - fy) + (r[w] * (1 - fx) + r[w + 1] * fx) * fy;
    }
};

struct Point {
    float x, y;            // центр подмножества на опорном кадре
    // предвычисленное для IC-GN
    std::vector<float> f;  // нормированные (f-fm) значения
    std::vector<float> J;  // n*6 Якобиан крутизны
    float H[36];           // обратная матрица Гессе
    float fm, fs;
    bool ok;
    float p[6];            // текущие параметры u ux uy v vx vy
};

struct Ctx {
    Img ref, cur;
    std::vector<Point> pts;
    int nx = 0, ny = 0, R = 15, search = 8, step = 10;
    int nthreads = 4;
    float minZncc = 0.6f;
};

void loadLuma(Img& im, const uint8_t* d, int w, int h, int stride) {
    im.w = w; im.h = h; im.p.resize((size_t)w * h);
    for (int y = 0; y < h; ++y) {
        const uint8_t* s = d + (size_t)y * stride;
        float* o = &im.p[(size_t)y * w];
        for (int x = 0; x < w; ++x) o[x] = s[x];
    }
}

bool inv6(const double* A, float* out) {
    double m[6][12];
    for (int i = 0; i < 6; ++i) for (int j = 0; j < 12; ++j)
        m[i][j] = j < 6 ? A[i * 6 + j] : (j - 6 == i);
    for (int c = 0; c < 6; ++c) {
        int piv = c;
        for (int r = c + 1; r < 6; ++r) if (std::fabs(m[r][c]) > std::fabs(m[piv][c])) piv = r;
        if (std::fabs(m[piv][c]) < 1e-12) return false;
        if (piv != c) for (int j = 0; j < 12; ++j) std::swap(m[c][j], m[piv][j]);
        double d = m[c][c];
        for (int j = 0; j < 12; ++j) m[c][j] /= d;
        for (int r = 0; r < 6; ++r) if (r != c) {
            double k = m[r][c];
            for (int j = 0; j < 12; ++j) m[r][j] -= k * m[c][j];
        }
    }
    for (int i = 0; i < 6; ++i) for (int j = 0; j < 6; ++j) out[i * 6 + j] = (float)m[i][j + 6];
    return true;
}

void prepPoint(const Img& f, Point& P, int R) {
    int n = (2 * R + 1) * (2 * R + 1);
    P.f.resize(n); P.J.resize((size_t)n * 6);
    int cx = (int)P.x, cy = (int)P.y;
    double s = 0;
    int k = 0;
    for (int dy = -R; dy <= R; ++dy) for (int dx = -R; dx <= R; ++dx) s += f.at(cx + dx, cy + dy);
    P.fm = (float)(s / n);
    double ss = 0;
    for (int dy = -R; dy <= R; ++dy) for (int dx = -R; dx <= R; ++dx) {
        float v = f.at(cx + dx, cy + dy) - P.fm; ss += v * v;
    }
    P.fs = (float)std::sqrt(ss);
    double H[36] = {0};
    k = 0;
    for (int dy = -R; dy <= R; ++dy) for (int dx = -R; dx <= R; ++dx, ++k) {
        int x = cx + dx, y = cy + dy;
        P.f[k] = f.at(x, y) - P.fm;
        // градиент Собеля (меньше шума, чем центральная разность)
        float gx = ((f.at(x + 1, y - 1) + 2 * f.at(x + 1, y) + f.at(x + 1, y + 1)) -
                    (f.at(x - 1, y - 1) + 2 * f.at(x - 1, y) + f.at(x - 1, y + 1))) * 0.125f;
        float gy = ((f.at(x - 1, y + 1) + 2 * f.at(x, y + 1) + f.at(x + 1, y + 1)) -
                    (f.at(x - 1, y - 1) + 2 * f.at(x, y - 1) + f.at(x + 1, y - 1))) * 0.125f;
        float* j = &P.J[(size_t)k * 6];
        j[0] = gx; j[1] = gx * dx; j[2] = gx * dy; j[3] = gy; j[4] = gy * dx; j[5] = gy * dy;
        for (int a = 0; a < 6; ++a) for (int b = 0; b < 6; ++b) H[a * 6 + b] += j[a] * j[b];
    }
    P.ok = P.fs > 2.0f * std::sqrt((float)n) && inv6(H, P.H);  // отбраковка бестекстурных зон
    std::memset(P.p, 0, sizeof(P.p));
}

// целочисленный ZNCC-поиск; возвращает лучший коэффициент
float zncc(const Ctx& c, const Point& P, int ux, int vy) {
    const int R = c.R;
    int cx = (int)P.x + ux, cy = (int)P.y + vy;
    if (cx - R < 1 || cy - R < 1 || cx + R >= c.cur.w - 2 || cy + R >= c.cur.h - 2) return -2;
    double sg = 0, sgg = 0, sfg = 0;
    int k = 0;
    for (int dy = -R; dy <= R; ++dy) {
        const float* g = &c.cur.p[(size_t)(cy + dy) * c.cur.w + cx - R];
        for (int dx = 0; dx <= 2 * R; ++dx, ++k) {
            float v = g[dx]; sg += v; sgg += v * v; sfg += P.f[k] * v;
        }
    }
    int n = k;
    double var = sgg - sg * sg / n;
    if (var < 1e-6) return -2;
    return (float)(sfg / (P.fs * std::sqrt(var)));   // sum(f')=0, поэтому среднее g не нужно в числителе
}

// IC-GN; возвращает ZNCC итоговой подгонки
float icgn(const Ctx& c, Point& P) {
    const int R = c.R, n = (2 * R + 1) * (2 * R + 1);
    float* p = P.p;
    std::vector<float> g(n);
    float zn = -1;
    for (int it = 0; it < 20; ++it) {
        // деформированное подмножество текущего кадра
        double sg = 0;
        int k = 0;
        for (int dy = -R; dy <= R; ++dy) for (int dx = -R; dx <= R; ++dx, ++k) {
            float x = P.x + dx + p[0] + p[1] * dx + p[2] * dy;
            float y = P.y + dy + p[3] + p[4] * dx + p[5] * dy;
            if (x < 1 || y < 1 || x >= c.cur.w - 2 || y >= c.cur.h - 2) return -2;
            g[k] = c.cur.bilin(x, y); sg += g[k];
        }
        float gm = (float)(sg / n);
        double ss = 0;
        for (int i = 0; i < n; ++i) { g[i] -= gm; ss += (double)g[i] * g[i]; }
        float gs = (float)std::sqrt(ss);
        if (gs < 1e-3f) return -2;
        float r = P.fs / gs;
        double b[6] = {0}, cc = 0;
        for (int i = 0; i < n; ++i) {
            float e = P.f[i] - r * g[i];   // ZNSSD невязка в масштабе f
            cc += P.f[i] * g[i];
            const float* j = &P.J[(size_t)i * 6];
            for (int a = 0; a < 6; ++a) b[a] += j[a] * e;
        }
        zn = (float)(cc / (P.fs * gs));
        float d[6];
        for (int a = 0; a < 6; ++a) { double s = 0; for (int q = 0; q < 6; ++q) s += P.H[a * 6 + q] * b[q]; d[a] = (float)(-s); }
        // композиция W(p) <- W(p) o W(dp)^-1
        float A[9] = {1 + p[1], p[2], p[0], p[4], 1 + p[5], p[3], 0, 0, 1};
        float Dm[9] = {1 + d[1], d[2], d[0], d[4], 1 + d[5], d[3], 0, 0, 1};
        float det = Dm[0] * Dm[4] - Dm[1] * Dm[3];
        if (std::fabs(det) < 1e-9f) break;
        float Di[9] = {Dm[4] / det, -Dm[1] / det, (Dm[1] * Dm[5] - Dm[4] * Dm[2]) / det,
                       -Dm[3] / det, Dm[0] / det, (Dm[3] * Dm[2] - Dm[0] * Dm[5]) / det, 0, 0, 1};
        float M[9];
        for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j)
            M[i * 3 + j] = A[i * 3] * Di[j] + A[i * 3 + 1] * Di[3 + j] + A[i * 3 + 2] * Di[6 + j];
        p[0] = M[2]; p[1] = M[0] - 1; p[2] = M[1]; p[3] = M[5]; p[4] = M[3]; p[5] = M[4] - 1;
        float conv = d[0] * d[0] + d[3] * d[3] + (d[1] * d[1] + d[2] * d[2] + d[4] * d[4] + d[5] * d[5]) * R * R;
        if (conv < 1e-6f) break;
    }
    return zn;
}

template <class F> void parallel(int n, int th, F fn) {
    std::atomic<int> next{0};
    std::vector<std::thread> ts;
    for (int t = 0; t < th; ++t) ts.emplace_back([&] { int i; while ((i = next.fetch_add(4)) < n) for (int j = i; j < std::min(n, i + 4); ++j) fn(j); });
    for (auto& t : ts) t.join();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_dic_app_DicNative_create(JNIEnv*, jclass) {
    auto* c = new Ctx();
    c->nthreads = std::max(2u, std::thread::hardware_concurrency());
    return (jlong)c;
}

JNIEXPORT void JNICALL Java_com_dic_app_DicNative_destroy(JNIEnv*, jclass, jlong h) { delete (Ctx*)h; }

// Возвращает [nx, ny]; координаты узлов можно получить через gridCoords
JNIEXPORT jintArray JNICALL Java_com_dic_app_DicNative_setReference(JNIEnv* env, jclass, jlong h,
        jobject buf, jint w, jint hh, jint stride, jint x0, jint y0, jint x1, jint y1,
        jint subset, jint step, jint search, jfloat minZncc) {
    auto* c = (Ctx*)h;
    loadLuma(c->ref, (const uint8_t*)env->GetDirectBufferAddress(buf), w, hh, stride);
    c->R = std::max(5, subset / 2); c->step = std::max(2, step); c->search = search; c->minZncc = minZncc;
    int m = c->R + 2;
    x0 = std::max(x0, m); y0 = std::max(y0, m); x1 = std::min(x1, w - m - 1); y1 = std::min(y1, hh - m - 1);
    c->nx = x1 > x0 ? (x1 - x0) / c->step + 1 : 0;
    c->ny = y1 > y0 ? (y1 - y0) / c->step + 1 : 0;
    c->pts.assign((size_t)c->nx * c->ny, Point());
    for (int j = 0; j < c->ny; ++j) for (int i = 0; i < c->nx; ++i) {
        Point& P = c->pts[(size_t)j * c->nx + i];
        P.x = (float)(x0 + i * c->step); P.y = (float)(y0 + j * c->step);
    }
    parallel((int)c->pts.size(), c->nthreads, [&](int i) { prepPoint(c->ref, c->pts[i], c->R); });
    jint r[2] = {c->nx, c->ny};
    jintArray a = env->NewIntArray(2); env->SetIntArrayRegion(a, 0, 2, r);
    return a;
}

JNIEXPORT void JNICALL Java_com_dic_app_DicNative_gridCoords(JNIEnv* env, jclass, jlong h, jfloatArray xy) {
    auto* c = (Ctx*)h;
    std::vector<float> o(c->pts.size() * 2);
    for (size_t i = 0; i < c->pts.size(); ++i) { o[2 * i] = c->pts[i].x; o[2 * i + 1] = c->pts[i].y; }
    env->SetFloatArrayRegion(xy, 0, (jsize)o.size(), o.data());
}

// out: на точку 7 float: u, v, ux, uy, vx, vy, zncc (NaN для отбракованных). Возвращает время, мс.
JNIEXPORT jfloat JNICALL Java_com_dic_app_DicNative_process(JNIEnv* env, jclass, jlong h,
        jobject buf, jint w, jint hh, jint stride, jfloatArray out) {
    auto* c = (Ctx*)h;
    auto t0 = std::chrono::steady_clock::now();
    if (w != c->ref.w || hh != c->ref.h) return -1;
    loadLuma(c->cur, (const uint8_t*)env->GetDirectBufferAddress(buf), w, hh, stride);
    int N = (int)c->pts.size();
    std::vector<float> res((size_t)N * 7);
    const float nan = std::nanf("");
    parallel(N, c->nthreads, [&](int i) {
        Point& P = c->pts[i];
        float* o = &res[(size_t)i * 7];
        if (!P.ok) { for (int q = 0; q < 7; ++q) o[q] = nan; return; }
        // прогноз — предыдущее решение; при плохом результате — полный поиск
        int pu = (int)std::lround(P.p[0]), pv = (int)std::lround(P.p[3]);
        float best = -2; int bu = pu, bv = pv;
        int S = c->search;
        for (int pass = 0; pass < 2; ++pass) {
            int s = pass == 0 ? std::min(3, S) : S;
            for (int dv = -s; dv <= s; ++dv) for (int du = -s; du <= s; ++du) {
                float z = zncc(*c, P, pu + du, pv + dv);
                if (z > best) { best = z; bu = pu + du; bv = pv + dv; }
            }
            if (best > 0.8f) break;
        }
        float keep[6]; std::memcpy(keep, P.p, sizeof keep);
        bool reuse = (std::lround(P.p[0]) == bu && std::lround(P.p[3]) == bv);
        if (!reuse) { std::memset(P.p, 0, sizeof P.p); P.p[0] = (float)bu; P.p[3] = (float)bv; }
        float z = icgn(*c, P);
        if (z < c->minZncc || !std::isfinite(P.p[0])) {
            std::memcpy(P.p, keep, sizeof keep);
            for (int q = 0; q < 7; ++q) o[q] = nan; o[6] = z; return;
        }
        o[0] = P.p[0]; o[1] = P.p[3]; o[2] = P.p[1]; o[3] = P.p[2]; o[4] = P.p[4]; o[5] = P.p[5]; o[6] = z;
    });
    env->SetFloatArrayRegion(out, 0, (jsize)res.size(), res.data());
    return std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

// резкость (дисперсия Лапласиана) в прямоугольнике — индикатор фокуса
JNIEXPORT jfloat JNICALL Java_com_dic_app_DicNative_sharpness(JNIEnv* env, jclass, jobject buf,
        jint w, jint h, jint stride, jint x0, jint y0, jint x1, jint y1) {
    auto* d = (const uint8_t*)env->GetDirectBufferAddress(buf);
    x0 = std::max(1, x0); y0 = std::max(1, y0); x1 = std::min(w - 2, x1); y1 = std::min(h - 2, y1);
    double s = 0, ss = 0; long n = 0;
    for (int y = y0; y <= y1; y += 2) for (int x = x0; x <= x1; x += 2) {
        const uint8_t* p = d + (size_t)y * stride + x;
        int l = 4 * p[0] - p[-1] - p[1] - p[-stride] - p[stride];
        s += l; ss += (double)l * l; ++n;
    }
    if (!n) return 0;
    double m = s / n;
    return (float)(ss / n - m * m);
}

}
