// The only bridge between Kotlin and libghostty-vt.
//
// One Term mirrors one remote Rex terminal, so replies a terminal would send to its program are not wired.
// A Term is used by one thread only, so nothing here locks.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <ghostty/vt.h>

typedef struct {
    GhosttyTerminal t;
    GhosttyRenderState rs;
    GhosttyRenderStateRowIterator rows;
    GhosttyRenderStateRowCells cells;
    char *apc;
    size_t apc_len;
    size_t apc_cap;
    GhosttyAllocator allocator;
    size_t memory_used;
    size_t memory_limit;
    bool budget_failed;
    GhosttySearch search;
    GhosttySelection *matches;
    size_t matches_cap;
    bool above;
    bool held;
    int64_t held_at;  // CLOCK_MONOTONIC ms
    GhosttyTerminalScrollbar held_bar;
} Term;

#define FN(name) Java_dev_mahali_hatch_ghostty_GhosttyTerminal_##name
#define TERM ((Term *)(intptr_t)handle)
#define UNUSED (void)cls

static void *budget_alloc(void *ctx, size_t len, uint8_t alignment, uintptr_t ret) {
    (void)ret;
    Term *term = ctx;
    if (alignment >= sizeof(size_t) * 8 || len > term->memory_limit - term->memory_used) {
        term->budget_failed = true;
        return NULL;
    }
    size_t align = (size_t)1 << alignment;
    if (align < sizeof(void *)) align = sizeof(void *);
    void *memory = NULL;
    if (posix_memalign(&memory, align, len ? len : 1) != 0) { term->budget_failed = true; return NULL; }
    term->memory_used += len;
    return memory;
}

// No in-place resize: live bytes stay a true bound, peak during a copy included.
static bool budget_resize(void *ctx, void *memory, size_t len, uint8_t alignment, size_t next, uintptr_t ret) {
    (void)ctx; (void)memory; (void)len; (void)alignment; (void)next; (void)ret;
    return false;
}

static void *budget_remap(void *ctx, void *memory, size_t len, uint8_t alignment, size_t next, uintptr_t ret) {
    (void)ctx; (void)memory; (void)len; (void)alignment; (void)next; (void)ret;
    return NULL;
}

static void budget_free(void *ctx, void *memory, size_t len, uint8_t alignment, uintptr_t ret) {
    (void)alignment; (void)ret;
    Term *term = ctx;
    term->memory_used -= len;
    free(memory);
}

static const GhosttyAllocatorVtable budget_vtable = {
    .alloc = budget_alloc, .resize = budget_resize, .remap = budget_remap, .free = budget_free,
};

// Rex messages are APC "rex1;..."; keep the latest so the caller applies it before any later byte.
static void on_unknown(GhosttyTerminal t, void *userdata, const GhosttyTerminalUnknownSequence *seq) {
    (void)t;
    Term *term = userdata;
    if (seq->tag != GHOSTTY_TERMINAL_UNKNOWN_SEQUENCE_APC || seq->value.apc.truncated) return;
    GhosttyString s = seq->value.apc.content;
    if (s.len < 5 || memcmp(s.ptr, "rex1;", 5) != 0) return;
    if (s.len + 1 > term->apc_cap) {
        char *p = realloc(term->apc, s.len + 1);
        if (!p) return;
        term->apc = p;
        term->apc_cap = s.len + 1;
    }
    memcpy(term->apc, s.ptr, s.len);
    term->apc[s.len] = 0;
    term->apc_len = s.len;
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// Mode 2026 hold: capture the frame here, even if more bytes follow in this write.
static void on_render_hold(GhosttyTerminal t, void *userdata, bool held) {
    Term *term = userdata;
    term->held = held && ghostty_render_state_update(term->rs, t) == GHOSTTY_SUCCESS;
    if (!term->held) return;
    term->held_at = now_ms();
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &term->held_bar);
}

static void end_hold(Term *term) {
    if (!term->held) return;
    GhosttyTerminalModeConfig mode = {.mode = GHOSTTY_MODE_SYNC_OUTPUT, .value = false};
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_MODE, &mode);
    term->held = false;
}

static void configure(Term *term) {
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_USERDATA, term);
    if (term->memory_limit == 0) {
        ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_UNKNOWN_SEQUENCE, (const void *)on_unknown);
        ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)on_render_hold);
    }
    size_t unknown_max = 64 * 1024;  // a full theme message is about 4 KB
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_UNKNOWN_MAX_BYTES, &unknown_max);
    size_t continuation = 0;
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &continuation);
}

static jlong new_terminal(jint cols, jint rows, size_t memory_limit) {
    Term *term = calloc(1, sizeof(Term));
    if (!term) return 0;
    term->memory_limit = memory_limit;
    term->allocator = (GhosttyAllocator){ .ctx = term, .vtable = &budget_vtable };
    const GhosttyAllocator *allocator = memory_limit ? &term->allocator : NULL;
    if (ghostty_terminal_new(allocator, &term->t, (uint16_t)cols, (uint16_t)rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_new(allocator, &term->rs) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(allocator, &term->rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(allocator, &term->cells) != GHOSTTY_SUCCESS) {
        ghostty_render_state_row_cells_free(term->cells);
        ghostty_render_state_row_iterator_free(term->rows);
        ghostty_render_state_free(term->rs);
        ghostty_terminal_free(term->t);
        free(term);
        return 0;
    }
    configure(term);
    return (jlong)(intptr_t)term;
}

JNIEXPORT jlong JNICALL FN(nativeNew)(JNIEnv *env, jclass cls, jint cols, jint rows) {
    (void)env; UNUSED;
    return new_terminal(cols, rows, 0);
}

JNIEXPORT jlong JNICALL FN(nativeNewBounded)(JNIEnv *env, jclass cls, jint cols, jint rows, jlong limit) {
    (void)env; UNUSED;
    if (limit <= 0 || (uint64_t)limit > SIZE_MAX) return 0;
    return new_terminal(cols, rows, (size_t)limit);
}

JNIEXPORT jboolean JNICALL FN(nativeBudgetFailed)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; UNUSED;
    return TERM->budget_failed;
}

JNIEXPORT jlong JNICALL FN(nativeBudgetUsed)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; UNUSED;
    return (jlong)TERM->memory_used;
}

JNIEXPORT void JNICALL FN(nativeFree)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; UNUSED;
    Term *term = TERM;
    if (!term) return;
    ghostty_render_state_row_cells_free(term->cells);
    ghostty_render_state_row_iterator_free(term->rows);
    ghostty_render_state_free(term->rs);
    ghostty_search_free(term->search);
    ghostty_terminal_free(term->t);
    free(term->matches);
    free(term->apc);
    free(term);
}

// Returns 0, or the libghostty error code.
JNIEXPORT jint JNICALL FN(nativeRestore)(JNIEnv *env, jclass cls, jlong handle, jobject buffer, jint offset, jint length) {
    UNUSED;
    Term *term = TERM;
    const uint8_t *bytes = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buffer) + offset;
    GhosttySnapshotDecoder dec = NULL;
    GhosttyTerminal restored = NULL;
    GhosttyResult r = ghostty_snapshot_decoder_new_buf(NULL, &dec, bytes, (size_t)length);
    if (r == GHOSTTY_SUCCESS) {
        bool compress = true;
        ghostty_snapshot_decoder_set(dec, GHOSTTY_SNAPSHOT_DECODER_OPT_COMPRESS_HISTORY, &compress);
        r = ghostty_snapshot_decoder_decode(dec, &restored);
    }
    ghostty_snapshot_decoder_free(dec);
    if (r != GHOSTTY_SUCCESS) return (jint)r;
    // A search is bound to the terminal it was made with: drop it with that terminal.
    ghostty_search_free(term->search);
    term->search = NULL;
    ghostty_terminal_free(term->t);
    term->t = restored;
    term->held = false;  // a hold belongs to the program on the old terminal
    configure(term);
    return 0;
}

// Returns bytes consumed; bit 31 set when an APC was captured (read it with nativeTakeApc).
JNIEXPORT jint JNICALL FN(nativeWrite)(JNIEnv *env, jclass cls, jlong handle, jobject buffer, jint offset, jint length) {
    UNUSED;
    Term *term = TERM;
    const uint8_t *bytes = (const uint8_t *)(*env)->GetDirectBufferAddress(env, buffer) + offset;
    term->apc_len = 0;
    size_t done = 0;
    while (done < (size_t)length && term->apc_len == 0) {
        GhosttyTerminalWriteStop stop = GHOSTTY_INIT_SIZED(GhosttyTerminalWriteStop);
        ghostty_terminal_vt_write_until(term->t, bytes + done, (size_t)length - done, GHOSTTY_TERMINAL_BOUNDARY_APC_END, &stop);
        if (stop.consumed == 0) break;
        done += stop.consumed;
        if (!stop.reached) break;
    }
    return (jint)done | (term->apc_len ? (jint)0x80000000 : 0);
}

JNIEXPORT jstring JNICALL FN(nativeTakeApc)(JNIEnv *env, jclass cls, jlong handle) {
    UNUSED;
    Term *term = TERM;
    if (term->apc_len == 0) return NULL;
    term->apc_len = 0;
    return (*env)->NewStringUTF(env, term->apc);  // JSON: valid UTF-8 without NULs
}

JNIEXPORT void JNICALL FN(nativeResize)(JNIEnv *env, jclass cls, jlong handle, jint cols, jint rows, jint cw, jint ch) {
    (void)env; UNUSED;
    ghostty_terminal_resize(TERM->t, (uint16_t)cols, (uint16_t)rows, (uint32_t)cw, (uint32_t)ch);
}

static GhosttyColorRgb rgb(jint c) { return (GhosttyColorRgb){(uint8_t)(c >> 16), (uint8_t)(c >> 8), (uint8_t)c}; }
static jint pack(GhosttyColorRgb c) { return (c.r << 16) | (c.g << 8) | c.b; }

JNIEXPORT void JNICALL FN(nativeSetColors)(JNIEnv *env, jclass cls, jlong handle, jint fg, jint bg, jint cursor, jintArray palette) {
    UNUSED;
    Term *term = TERM;
    GhosttyColorRgb f = rgb(fg), b = rgb(bg), c = rgb(cursor);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, fg < 0 ? NULL : &f);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, bg < 0 ? NULL : &b);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, cursor < 0 ? NULL : &c);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, NULL);
    jsize n = palette ? (*env)->GetArrayLength(env, palette) : 0;
    if (n < 2) return;
    GhosttyColorRgb pal[256];
    if (ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, &pal) != GHOSTTY_SUCCESS) return;
    jint *p = (*env)->GetIntArrayElements(env, palette, NULL);
    for (jsize i = 0; i + 1 < n; i += 2)
        if (p[i] >= 0 && p[i] < 256) pal[p[i]] = rgb(p[i + 1]);
    (*env)->ReleaseIntArrayElements(env, palette, p, JNI_ABORT);
    ghostty_terminal_set(term->t, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, &pal);
}

// The cursor a program gets when it has not asked for one: style 0 bar, 1 block, 2 underline.
JNIEXPORT void JNICALL FN(nativeCursorDefaults)(JNIEnv *env, jclass cls, jlong handle, jint style, jboolean blink) {
    (void)env; UNUSED;
    GhosttyTerminalCursorStyle st = (GhosttyTerminalCursorStyle)style;
    bool b = blink;
    ghostty_terminal_set(TERM->t, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE, &st);
    ghostty_terminal_set(TERM->t, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, &b);
}

static GhosttyTerminalScrollbar scrollbar(Term *term) {
    GhosttyTerminalScrollbar bar = {0};
    ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
    return bar;
}

static void scroll_viewport(Term *term, jint delta) {
    GhosttyTerminalScrollViewport s = {0};
    if (delta == 0) s.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
    else { s.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA; s.value.delta = delta; }
    ghostty_terminal_scroll_viewport(term->t, s);
}

// Returns the viewport's first row among all rows afterwards; scrolling ends a hold.
JNIEXPORT jint JNICALL FN(nativeScroll)(JNIEnv *env, jclass cls, jlong handle, jint delta) {
    (void)env; UNUSED;
    end_hold(TERM);
    scroll_viewport(TERM, delta);
    return (jint)scrollbar(TERM).offset;
}

JNIEXPORT jint JNICALL FN(nativeViewportTop)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; UNUSED;
    return (jint)scrollbar(TERM).offset;
}

static jstring utf8(JNIEnv *env, const uint8_t *ptr, size_t len) {
    char *z = malloc(len + 1);
    if (!z) return NULL;
    memcpy(z, ptr, len);
    z[len] = 0;
    jstring s = (*env)->NewStringUTF(env, z);
    free(z);
    return s;
}

static bool mode_on(Term *term, GhosttyMode mode) {
    GhosttyTerminalModeConfig cfg = {.mode = mode, .value = false};
    return ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_MODE, &cfg) == GHOSTTY_SUCCESS && cfg.value;
}

enum {
    H_COLS, H_ROWS, H_CURSOR_X, H_CURSOR_Y, H_CURSOR, H_FG, H_BG, H_CURSOR_COLOR,
    H_SCROLL_TOTAL, H_SCROLL_OFFSET, H_EXTRAS_USED = 11, H_SCREEN, H_ROWS_ABOVE, H_SIZE = 16,
};
enum {
    F_BOLD = 1, F_ITALIC = 2, F_FAINT = 4, F_UNDERLINE = 8, F_STRIKE = 16, F_INVISIBLE = 32,
    F_DEFAULT_BG = 128, F_WIDE = 256, F_SPACER = 512, F_MATCH = 1024, F_MATCH_CURRENT = 2048,
};

// Called once per frame: a long scrollback is searched over several frames, not one.
static void search_step(Term *term) {
    if (!term->search) return;
    ghostty_search_feed(term->search);
    GhosttySearchStatus status = GHOSTTY_SEARCH_STATUS_RUNNING;
    for (int i = 0; i < 64 && status == GHOSTTY_SEARCH_STATUS_RUNNING; i++)
        if (ghostty_search_tick(term->search, &status) != GHOSTTY_SUCCESS) break;
}

static void mark_match(Term *term, const GhosttySelection *sel, int32_t *cells, int cols, int rows, int32_t flag) {
    GhosttyPointCoordinate a, b;
    if (ghostty_terminal_point_from_grid_ref(term->t, &sel->start, GHOSTTY_POINT_TAG_VIEWPORT, &a) != GHOSTTY_SUCCESS ||
        ghostty_terminal_point_from_grid_ref(term->t, &sel->end, GHOSTTY_POINT_TAG_VIEWPORT, &b) != GHOSTTY_SUCCESS) return;
    long from = (long)a.y * cols + a.x, to = (long)b.y * cols + b.x;
    if (from > to) { long t = from; from = to; to = t; }
    long last = (long)cols * rows - 1;
    if (from < 0) from = 0;
    if (to > last) to = last;
    for (long i = from; i <= to; i++) cells[i * 4 + 3] |= flag;
}

static void mark_matches(Term *term, int32_t *cells, int cols, int rows) {
    if (!term->search) return;
    GhosttySelectionBuffer buf = {.ptr = term->matches, .cap = term->matches_cap, .len = 0};
    GhosttyResult r = ghostty_search_get(term->search, GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES, &buf);
    if (r == GHOSTTY_OUT_OF_SPACE) {
        size_t cap = buf.len;
        GhosttySelection *grown = realloc(term->matches, cap * sizeof(GhosttySelection));
        if (!grown) return;
        term->matches = grown;
        term->matches_cap = cap;
        for (size_t i = 0; i < cap; i++) grown[i] = (GhosttySelection)GHOSTTY_INIT_SIZED(GhosttySelection);
        buf = (GhosttySelectionBuffer){.ptr = term->matches, .cap = term->matches_cap, .len = 0};
        r = ghostty_search_get(term->search, GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES, &buf);
    }
    if (r == GHOSTTY_SUCCESS)
        for (size_t i = 0; i < buf.len; i++) mark_match(term, &term->matches[i], cells, cols, rows, F_MATCH);
    GhosttySelection current = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_search_get(term->search, GHOSTTY_SEARCH_DATA_SELECTED_MATCH, &current) == GHOSTTY_SUCCESS)
        mark_match(term, &current, cells, cols, rows, F_MATCH_CURRENT);
}

// Most cells are one unstyled codepoint and the raw cell says it all. libghostty resolves no fg
// without a style, nor bg except from a style or a bg-only cell, so other cells skip those queries (render.zig).
static void write_row(Term *term, int32_t *row, int cols, int32_t *extras, int32_t *extrasUsed, int32_t extrasCap, int32_t dfg, int32_t dbg) {
    static const GhosttyCellData raw_keys[] = {
        GHOSTTY_CELL_DATA_CODEPOINT, GHOSTTY_CELL_DATA_CONTENT_TAG, GHOSTTY_CELL_DATA_WIDE, GHOSTTY_CELL_DATA_HAS_STYLING,
    };
    // Colours fail when the cell has none, which ends a batch there: out_written says which did.
    static const GhosttyRenderStateRowCellsData style_keys[] = {
        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
    };
    ghostty_render_state_row_get(term->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &term->cells);
    GhosttyCellsView raws = {0};
    ghostty_render_state_row_get(term->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS_RAW, &raws);
    int x = 0;
    for (; x < cols && (size_t)x < raws.len; x++) {
        int32_t *c = row + (size_t)x * 4;
        uint32_t cp = 0;
        GhosttyCellContentTag tag = GHOSTTY_CELL_CONTENT_CODEPOINT;
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        bool styled = false;
        void *raw_values[] = {&cp, &tag, &wide, &styled};
        ghostty_cell_get_multi(raws.ptr[x], 4, raw_keys, raw_values, NULL);
        bool bgOnly = tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE || tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB;
        if (tag == GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME || styled || bgOnly)
            ghostty_render_state_row_cells_select(term->cells, (uint16_t)x);

        c[0] = (int32_t)cp;
        if (tag == GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME) {
            uint32_t glen = 0, cps[16];
            ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
            // GRAPHEMES_BUF writes every codepoint: a longer cluster keeps its first one only.
            if (glen > 1 && glen <= 16 && *extrasUsed + 1 + (int32_t)glen <= extrasCap &&
                ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps) == GHOSTTY_SUCCESS) {
                c[0] = -(*extrasUsed + 1);
                extras[(*extrasUsed)++] = (int32_t)glen;
                for (uint32_t i = 0; i < glen; i++) extras[(*extrasUsed)++] = (int32_t)cps[i];
            }
        }

        GhosttyStyle st = GHOSTTY_INIT_SIZED(GhosttyStyle);
        GhosttyColorRgb fgc, bgc;
        int hasFg = 0, hasBg = 0;
        if (styled) {
            void *style_values[] = {&st, &fgc, &bgc};
            size_t written = 0;
            ghostty_render_state_row_cells_get_multi(term->cells, 3, style_keys, style_values, &written);
            hasFg = written >= 2;
            hasBg = written == 3 || (written == 1 &&
                ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bgc) == GHOSTTY_SUCCESS);
        } else if (bgOnly) {
            hasBg = ghostty_render_state_row_cells_get(term->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bgc) == GHOSTTY_SUCCESS;
        }
        int32_t fg = hasFg ? pack(fgc) : dfg;
        int32_t bg = hasBg ? pack(bgc) : dbg;

        int32_t flags = 0;
        if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= F_WIDE;
        else if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) flags |= F_SPACER;
        if (st.inverse) { int32_t t = fg; fg = bg; bg = t; hasBg = 1; }
        if (!hasBg) flags |= F_DEFAULT_BG;
        if (st.bold) flags |= F_BOLD;
        if (st.italic) flags |= F_ITALIC;
        if (st.faint) flags |= F_FAINT;
        if (st.underline) flags |= F_UNDERLINE;
        if (st.strikethrough) flags |= F_STRIKE;
        if (st.invisible) flags |= F_INVISIBLE;
        c[1] = fg;
        c[2] = bg;
        c[3] = flags;
    }
    for (; x < cols; x++) {
        int32_t *c = row + (size_t)x * 4;
        c[0] = 0; c[1] = dfg; c[2] = dbg; c[3] = F_DEFAULT_BG;
    }
}

// Layout (matches FrameBuffer.kt): H_SIZE header ints, 4 per cell (codepoint, fg, bg, flags), the
// row above if asked and present (H_ROWS_ABOVE), then extras. A multi-codepoint cell stores
// -(index+1); extras[index] is the count, then the codepoints.
// Returns 0, or the buffer size in bytes that this frame needs when the buffer is too small.
JNIEXPORT jint JNICALL FN(nativeFrame)(JNIEnv *env, jclass cls, jlong handle, jobject buffer, jint extrasCap, jboolean rowAbove) {
    UNUSED;
    Term *term = TERM;
    // One second is as long as a program may hold the screen (the limit libghostty suggests).
    // A smooth scroll starting needs the row above, which the held frame may not have.
    if (term->held && ((rowAbove && !term->above) || now_ms() - term->held_at >= 1000)) end_hold(term);
    if (!term->held) {
        // Overscan: a smooth scroll needs the row above. A new request applies from this update on.
        if (term->above != (bool)rowAbove) {
            GhosttyRenderStateOverscan request = {.above = rowAbove ? 1 : 0, .below = 0};
            ghostty_render_state_set(term->rs, GHOSTTY_RENDER_STATE_OPTION_OVERSCAN, &request);
            term->above = rowAbove;
        }
        if (ghostty_render_state_update(term->rs, term->t) != GHOSTTY_SUCCESS) return -1;
    }
    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    // Room for one row more than the viewport, so a frame with the row above always fits.
    jlong need = ((jlong)H_SIZE + (jlong)cols * (rows + 1) * 4 + extrasCap) * 4;
    if (capacity < need) return (jint)need;

    int32_t *out = (*env)->GetDirectBufferAddress(env, buffer);
    int32_t *cells = out + H_SIZE;

    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    int32_t dfg = pack(colors.foreground), dbg = pack(colors.background);

    GhosttyTerminalScrollbar bar = term->held ? term->held_bar : scrollbar(term);
    // Nothing is above the top of the scrollback. A held frame may have the row unasked: skip it.
    GhosttyRenderStateOverscan captured = {0};
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_OVERSCAN, &captured);
    int32_t above = rowAbove && captured.above > 0 ? 1 : 0;
    int32_t *extras = cells + (size_t)cols * (rows + above) * 4;
    int32_t extrasUsed = 0;

    GhosttyRenderStateCursor cur = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur);
    ghostty_render_state_get(term->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &term->rows);
    // Overscan rows come first; the renderer reads the row above after the viewport's rows.
    for (int i = 0; i < captured.above && ghostty_render_state_row_iterator_next(term->rows); i++)
        if (above) write_row(term, cells + (size_t)cols * rows * 4, cols, extras, &extrasUsed, extrasCap, dfg, dbg);
    int y = 0;
    while (y < rows && ghostty_render_state_row_iterator_next(term->rows)) {
        write_row(term, cells + (size_t)y * cols * 4, cols, extras, &extrasUsed, extrasCap, dfg, dbg);
        y++;
    }

    search_step(term);
    mark_matches(term, cells, cols, rows);

    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
    bool mouse = false;
    ghostty_terminal_get(term->t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);

    out[H_COLS] = cols;
    out[H_ROWS] = rows;
    out[H_CURSOR_X] = cur.viewport_has_value ? cur.viewport_x : -1;
    out[H_CURSOR_Y] = cur.viewport_has_value ? cur.viewport_y : -1;
    out[H_CURSOR] = (cur.visible ? 1 : 0) | ((int32_t)cur.visual_style << 2);
    out[H_FG] = dfg;
    out[H_BG] = dbg;
    out[H_CURSOR_COLOR] = colors.cursor_has_value ? pack(colors.cursor) : -1;
    out[H_SCROLL_TOTAL] = (int32_t)bar.total;
    out[H_SCROLL_OFFSET] = (int32_t)bar.offset;
    out[H_EXTRAS_USED] = extrasUsed;
    out[H_ROWS_ABOVE] = above;
    out[H_SCREEN] = (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE ? 1 : 0) | (mouse ? 2 : 0) |
                    (mode_on(term, GHOSTTY_MODE_ALT_SCROLL) ? 4 : 0) |
                    (term->held ? 16 : 0);
    return 0;
}

JNIEXPORT jstring JNICALL FN(nativeLinkAt)(JNIEnv *env, jclass cls, jlong handle, jint col, jint row) {
    UNUSED;
    GhosttyPoint pt = {0};
    pt.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    pt.value.coordinate.x = (uint16_t)col;
    pt.value.coordinate.y = (uint32_t)row;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(TERM->t, pt, &ref) != GHOSTTY_SUCCESS) return NULL;
    size_t len = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &len) != GHOSTTY_OUT_OF_SPACE || len == 0) return NULL;
    uint8_t *z = malloc(len);
    if (!z) return NULL;
    jstring out = ghostty_grid_ref_hyperlink_uri(&ref, z, len, &len) == GHOSTTY_SUCCESS ? utf8(env, z, len) : NULL;
    free(z);
    return out;
}

static bool screen_ref(Term *term, jint x, jint y, GhosttyGridRef *out) {
    GhosttyPoint p = {.tag = GHOSTTY_POINT_TAG_SCREEN};
    p.value.coordinate.x = (uint16_t)x;
    p.value.coordinate.y = (uint32_t)y;
    GhosttyGridRef r = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(term->t, p, &r) != GHOSTTY_SUCCESS) return false;
    *out = r;
    return true;
}

JNIEXPORT jstring JNICALL FN(nativeSelectionText)(JNIEnv *env, jclass cls, jlong handle, jint x1, jint y1, jint x2, jint y2) {
    UNUSED;
    Term *term = TERM;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (!screen_ref(term, x1, y1, &sel.start) || !screen_ref(term, x2, y2, &sel.end)) return NULL;
    GhosttyTerminalSelectionFormatOptions opts = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
    opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    opts.unwrap = true;
    opts.trim = true;
    opts.selection = &sel;
    uint8_t *buf = NULL;
    size_t len = 0;
    if (ghostty_terminal_selection_format_alloc(term->t, NULL, opts, &buf, &len) != GHOSTTY_SUCCESS) return NULL;
    jstring out = utf8(env, buf, len);
    ghostty_free(NULL, buf, len);
    return out;
}

JNIEXPORT jboolean JNICALL FN(nativeSelectAll)(JNIEnv *env, jclass cls, jlong handle, jintArray out) {
    UNUSED;
    Term *term = TERM;
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyPointCoordinate a, b;
    if (ghostty_terminal_select_all(term->t, &sel) != GHOSTTY_SUCCESS ||
        ghostty_terminal_point_from_grid_ref(term->t, &sel.start, GHOSTTY_POINT_TAG_SCREEN, &a) != GHOSTTY_SUCCESS ||
        ghostty_terminal_point_from_grid_ref(term->t, &sel.end, GHOSTTY_POINT_TAG_SCREEN, &b) != GHOSTTY_SUCCESS) return JNI_FALSE;
    jint v[4] = {a.x, (jint)a.y, b.x, (jint)b.y};
    (*env)->SetIntArrayRegion(env, out, 0, 4, v);
    return JNI_TRUE;
}

// Null or empty needle stops searching and frees it.
JNIEXPORT void JNICALL FN(nativeSearchSet)(JNIEnv *env, jclass cls, jlong handle, jstring needle) {
    UNUSED;
    Term *term = TERM;
    const char *z = needle ? (*env)->GetStringUTFChars(env, needle, NULL) : NULL;
    if (!z || !z[0]) {
        if (z) (*env)->ReleaseStringUTFChars(env, needle, z);
        ghostty_search_free(term->search);
        term->search = NULL;
        return;
    }
    if (!term->search && ghostty_search_new(NULL, &term->search, term->t) != GHOSTTY_SUCCESS) term->search = NULL;
    if (term->search) {
        GhosttyString s = {.ptr = (const uint8_t *)z, .len = strlen(z)};
        ghostty_search_set(term->search, GHOSTTY_SEARCH_OPT_NEEDLE, &s);
        search_step(term);
    }
    (*env)->ReleaseStringUTFChars(env, needle, z);
}

// Returns false when there is none.
JNIEXPORT jboolean JNICALL FN(nativeSearchSelect)(JNIEnv *env, jclass cls, jlong handle, jboolean older) {
    (void)env; UNUSED;
    Term *term = TERM;
    if (!term->search) return JNI_FALSE;
    end_hold(term);
    GhosttySearchOption opt = older ? GHOSTTY_SEARCH_OPT_SELECT_NEXT : GHOSTTY_SEARCH_OPT_SELECT_PREV;
    return ghostty_search_set(term->search, opt, NULL) == GHOSTTY_SUCCESS;
}

// out = { total matches, selected index (0 = newest) or -1, 1 when the search has caught up }.
JNIEXPORT void JNICALL FN(nativeSearchInfo)(JNIEnv *env, jclass cls, jlong handle, jintArray out) {
    UNUSED;
    Term *term = TERM;
    jint v[3] = {0, -1, 1};
    if (term->search) {
        size_t total = 0, index = 0;
        GhosttySearchStatus status = GHOSTTY_SEARCH_STATUS_COMPLETE;
        if (ghostty_search_get(term->search, GHOSTTY_SEARCH_DATA_TOTAL_MATCHES, &total) == GHOSTTY_SUCCESS) v[0] = (jint)total;
        if (ghostty_search_get(term->search, GHOSTTY_SEARCH_DATA_SELECTED_INDEX, &index) == GHOSTTY_SUCCESS) v[1] = (jint)index;
        ghostty_search_get(term->search, GHOSTTY_SEARCH_DATA_STATUS, &status);
        v[2] = status == GHOSTTY_SEARCH_STATUS_COMPLETE ? 1 : 0;
    }
    (*env)->SetIntArrayRegion(env, out, 0, 3, v);
}
