//! The Veil terminal core: remote terminal bytes in, normalized frames out.
//!
//! This is the boundary the design calls the VT worker. Everything a remote
//! host can say in the terminal's control languages (VT/ANSI escapes, OSC
//! strings) is interpreted here and nowhere else; what leaves is a
//! `veil.engine.v1.Frame` of plain cells, plus [`TermEvent`]s. The emulation
//! itself is `alacritty_terminal`, an implementation detail: none of its types
//! appear in this crate's API, so it can be replaced without touching the
//! protocol or any frontend.
//!
//! Remote requests that would reach outside the terminal are narrowed here:
//! OSC 52 may only *set* the clipboard (a bounded [`TermEvent::Clipboard`]
//! the frontend may refuse); reading the clipboard is never possible. Color
//! and size queries go unanswered.

use std::sync::{Arc, Mutex};

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::ClipboardType;
use alacritty_terminal::term::cell::{Cell, Flags};
use alacritty_terminal::term::{Config, Osc52, Term, TermMode};
use alacritty_terminal::vte::ansi::{Color, CursorShape, NamedColor, Processor};
use veil_protocol::v1;
use veil_protocol::{MAX_TITLE_CHARS, color};

/// Lines of scrollback kept per terminal.
pub const SCROLLBACK_LINES: usize = 5_000;

/// The most text one OSC 52 copy may carry; longer ones are dropped.
pub const MAX_CLIPBOARD_BYTES: usize = 1024 * 1024;

/// Something the remote side asked for that is not screen content.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TermEvent {
    /// Bytes to send back to the remote (answers to device-status and
    /// similar queries).
    Reply(Vec<u8>),
    Bell,
    /// OSC 52: the remote asks to put `text` on the clipboard (`primary`:
    /// the X11 primary selection rather than the clipboard). The frontend
    /// decides whether to allow it.
    Clipboard {
        text: String,
        primary: bool,
    },
}

#[derive(Clone, Copy, Debug)]
struct Size {
    cols: usize,
    rows: usize,
}

impl Dimensions for Size {
    fn total_lines(&self) -> usize {
        self.rows
    }

    fn screen_lines(&self) -> usize {
        self.rows
    }

    fn columns(&self) -> usize {
        self.cols
    }
}

/// Collects the events `Term` emits while it processes input.
#[derive(Clone, Default)]
struct Listener {
    events: Arc<Mutex<Vec<Event>>>,
}

impl EventListener for Listener {
    fn send_event(&self, event: Event) {
        match event {
            Event::PtyWrite(_)
            | Event::Bell
            | Event::Title(_)
            | Event::ResetTitle
            | Event::ClipboardStore(..) => {
                self.events
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push(event);
            }
            // Clipboard reads (never allowed), color and size queries, cursor
            // and redraw hints: nothing to forward.
            _ => {}
        }
    }
}

pub struct Terminal {
    term: Term<Listener>,
    parser: Processor,
    listener: Listener,
    title: String,
    seq: u64,
    /// The next frame must be full (resize, scroll, snapshot request).
    force_full: bool,
    /// What the last frame left the frontend showing, to send only
    /// differences: rows by index, and everything else.
    sent_rows: Vec<Option<v1::Row>>,
    sent_rest: Option<FrameRest>,
}

/// The non-row parts of a frame, compared to decide whether one is needed.
#[derive(Clone, PartialEq)]
struct FrameRest {
    cursor: v1::Cursor,
    modes: v1::Modes,
    title: String,
    scroll_offset: u32,
    history_lines: u32,
}

impl Terminal {
    /// A terminal of `cols` x `rows`. The caller has checked the bounds
    /// (`veil_protocol::check_size`).
    pub fn new(cols: u16, rows: u16) -> Terminal {
        let listener = Listener::default();
        let config = Config {
            scrolling_history: SCROLLBACK_LINES,
            // Copy only: a remote may offer text for the clipboard, never
            // read it.
            osc52: Osc52::OnlyCopy,
            ..Config::default()
        };
        let size = Size {
            cols: cols as usize,
            rows: rows as usize,
        };
        Terminal {
            term: Term::new(config, &size, listener.clone()),
            parser: Processor::new(),
            listener,
            title: String::new(),
            seq: 0,
            force_full: true,
            sent_rows: Vec::new(),
            sent_rest: None,
        }
    }

    /// Interprets bytes from the remote. Returns what must happen besides
    /// the screen change: replies to send back, bells.
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<TermEvent> {
        self.parser.advance(&mut self.term, bytes);
        let events = std::mem::take(
            &mut *self
                .listener
                .events
                .lock()
                .unwrap_or_else(|e| e.into_inner()),
        );
        let mut out = Vec::new();
        for event in events {
            match event {
                Event::PtyWrite(text) => out.push(TermEvent::Reply(text.into_bytes())),
                Event::Bell => out.push(TermEvent::Bell),
                Event::ClipboardStore(ty, text) if text.len() <= MAX_CLIPBOARD_BYTES => {
                    out.push(TermEvent::Clipboard {
                        text,
                        primary: matches!(ty, ClipboardType::Selection),
                    })
                }
                Event::Title(title) => self.title = sanitize_title(&title),
                Event::ResetTitle => self.title.clear(),
                _ => {}
            }
        }
        out
    }

    pub fn resize(&mut self, cols: u16, rows: u16) {
        self.term.resize(Size {
            cols: cols as usize,
            rows: rows as usize,
        });
        self.force_full = true;
    }

    /// Scrolls the view `lines` into history (negative: back towards the
    /// live screen).
    pub fn scroll(&mut self, lines: i32) {
        self.term.scroll_display(Scroll::Delta(lines));
        self.force_full = true;
    }

    pub fn scroll_to_bottom(&mut self) {
        self.term.scroll_display(Scroll::Bottom);
        self.force_full = true;
    }

    /// Makes the next frame a full one.
    pub fn request_full(&mut self) {
        self.force_full = true;
    }

    pub fn size(&self) -> (u16, u16) {
        (self.term.columns() as u16, self.term.screen_lines() as u16)
    }

    /// The changes since the last frame as a `Frame` (session_id left 0 for
    /// the caller to fill), or None if nothing visible changed.
    pub fn frame(&mut self) -> Option<v1::Frame> {
        let rows = self.term.screen_lines();
        let full = self.force_full;
        // Damage narrows which rows to look at. It also always includes the
        // cursor's line, so rows are compared with what was last sent.
        let candidates: Vec<usize> = if full {
            (0..rows).collect()
        } else {
            match self.term.damage() {
                alacritty_terminal::term::TermDamage::Full => (0..rows).collect(),
                alacritty_terminal::term::TermDamage::Partial(lines) => {
                    let mut v: Vec<usize> = lines.map(|d| d.line).filter(|&l| l < rows).collect();
                    v.sort_unstable();
                    v.dedup();
                    v
                }
            }
        };
        self.term.reset_damage();
        self.force_full = false;
        if full {
            self.sent_rows = vec![None; rows];
            self.sent_rest = None;
        }

        // The emulator can leave the view scrolled further back than the
        // history it still has (a reset, a resize that drops history);
        // indexing with that offset reads outside the grid. Snap back.
        let grid = self.term.grid();
        let offset = if grid.display_offset() > grid.history_size() {
            self.term.scroll_display(Scroll::Bottom);
            0
        } else {
            grid.display_offset()
        };
        let mut changed = Vec::new();
        for r in candidates {
            let row = self.row(r, offset);
            if self.sent_rows[r].as_ref() != Some(&row) {
                self.sent_rows[r] = Some(row.clone());
                changed.push(row);
            }
        }
        let rest = FrameRest {
            cursor: self.cursor(offset),
            modes: modes(*self.term.mode()),
            title: self.title.clone(),
            scroll_offset: offset as u32,
            history_lines: self.term.grid().history_size() as u32,
        };
        if !full && changed.is_empty() && self.sent_rest.as_ref() == Some(&rest) {
            return None;
        }
        self.sent_rest = Some(rest.clone());

        self.seq += 1;
        Some(v1::Frame {
            session_id: 0,
            seq: self.seq,
            size: Some(v1::TermSize {
                cols: self.term.columns() as u32,
                rows: rows as u32,
            }),
            full,
            rows: changed,
            cursor: Some(rest.cursor),
            modes: Some(rest.modes),
            title: rest.title,
            scroll_offset: rest.scroll_offset,
            history_lines: rest.history_lines,
        })
    }

    /// Viewport row `r`, as runs. Trailing blank default cells are left out.
    fn row(&self, r: usize, offset: usize) -> v1::Row {
        let line = Line(r as i32 - offset as i32);
        let row = &self.term.grid()[line];
        let cols = self.term.columns();
        let mut runs: Vec<v1::Run> = Vec::new();
        let mut col = 0;
        while col < cols {
            let cell = &row[Column(col)];
            if cell
                .flags
                .intersects(Flags::WIDE_CHAR_SPACER | Flags::LEADING_WIDE_CHAR_SPACER)
            {
                col += 1;
                continue;
            }
            let (fg, bg, mut flags) = attrs(cell);
            // A wide character needs two columns; in the last column (left
            // there by a resize) only one exists, so it is drawn narrow.
            let wide = cell.flags.contains(Flags::WIDE_CHAR) && col + 1 < cols;
            if !wide {
                flags &= !(v1::RunFlag::Wide as u32);
            }
            let width = if wide { 2 } else { 1 };
            // The emulator keeps some control characters in cells (a tab
            // where one landed, for copying); frames carry only visible text.
            let c = if cell.c.is_control() { ' ' } else { cell.c };
            let marks: Vec<char> = cell
                .zerowidth()
                .map(|z| z.iter().copied().filter(|m| !m.is_control()).collect())
                .unwrap_or_default();
            let cluster = !marks.is_empty();
            let mut text = String::new();
            text.push(c);
            text.extend(&marks);
            // Extend the previous run when attributes match and neither side
            // is a multi-scalar cluster (which must be a run of its own).
            if let Some(last) = runs.last_mut()
                && !cluster
                && last.fg == fg
                && last.bg == bg
                && last.flags == flags
                && last.col + last.cells == col as u32
                && last.text.chars().count() as u32 * width == last.cells
            {
                last.text.push(c);
                last.cells += width;
            } else {
                runs.push(v1::Run {
                    col: col as u32,
                    cells: width,
                    text,
                    fg,
                    bg,
                    flags,
                });
            }
            col += width as usize;
        }
        // Trailing spaces with the default background and nothing drawn
        // across them (inverse, underline, strikeout) are invisible: trim
        // them, dropping runs that become empty.
        let visible = v1::RunFlag::Inverse as u32
            | v1::RunFlag::Underline as u32
            | v1::RunFlag::Strikeout as u32
            | v1::RunFlag::Wide as u32;
        while let Some(last) = runs.last_mut() {
            if last.bg != color::DEFAULT || last.flags & visible != 0 {
                break;
            }
            // A multi-scalar cluster is one cell; only plain runs are trimmed.
            if last.text.chars().count() as u32 != last.cells {
                break;
            }
            let trimmed = last.text.trim_end_matches(' ');
            let removed = (last.text.len() - trimmed.len()) as u32;
            if removed == 0 {
                break;
            }
            last.cells -= removed;
            last.text.truncate(trimmed.len());
            if last.cells == 0 {
                runs.pop();
            } else {
                break;
            }
        }
        v1::Row {
            index: r as u32,
            runs,
        }
    }

    fn cursor(&self, offset: usize) -> v1::Cursor {
        let content = self.term.renderable_content();
        let point = content.cursor.point;
        let row = point.line.0 + offset as i32;
        let in_view = row >= 0 && (row as usize) < self.term.screen_lines();
        let shape = match content.cursor.shape {
            CursorShape::Block | CursorShape::HollowBlock => v1::CursorShape::Block,
            CursorShape::Underline => v1::CursorShape::Underline,
            CursorShape::Beam => v1::CursorShape::Beam,
            CursorShape::Hidden => v1::CursorShape::Unspecified,
        };
        // Scrolled back, the cursor's line can be below the view: it is then
        // hidden, but its position must still lie on the screen (the render
        // contract), so it is clamped.
        let last_row = self.term.screen_lines().saturating_sub(1) as i32;
        let last_col = self.term.columns().saturating_sub(1);
        v1::Cursor {
            row: row.clamp(0, last_row) as u32,
            col: point.column.0.min(last_col) as u32,
            visible: in_view && content.cursor.shape != CursorShape::Hidden,
            shape: shape as i32,
        }
    }
}

fn modes(mode: TermMode) -> v1::Modes {
    let mouse = if mode.contains(TermMode::MOUSE_MOTION) {
        v1::MouseMode::Motion
    } else if mode.contains(TermMode::MOUSE_DRAG) {
        v1::MouseMode::Drag
    } else if mode.contains(TermMode::MOUSE_REPORT_CLICK) {
        v1::MouseMode::Click
    } else {
        v1::MouseMode::None
    };
    v1::Modes {
        app_cursor: mode.contains(TermMode::APP_CURSOR),
        app_keypad: mode.contains(TermMode::APP_KEYPAD),
        bracketed_paste: mode.contains(TermMode::BRACKETED_PASTE),
        mouse: mouse as i32,
        mouse_sgr: mode.contains(TermMode::SGR_MOUSE),
        alt_screen: mode.contains(TermMode::ALT_SCREEN),
    }
}

fn attrs(cell: &Cell) -> (u32, u32, u32) {
    let f = cell.flags;
    let mut flags = 0;
    for (have, bit) in [
        (Flags::BOLD, v1::RunFlag::Bold),
        (Flags::ITALIC, v1::RunFlag::Italic),
        (Flags::ALL_UNDERLINES, v1::RunFlag::Underline),
        (Flags::INVERSE, v1::RunFlag::Inverse),
        (Flags::DIM, v1::RunFlag::Dim),
        (Flags::STRIKEOUT, v1::RunFlag::Strikeout),
        (Flags::HIDDEN, v1::RunFlag::Hidden),
        (Flags::WIDE_CHAR, v1::RunFlag::Wide),
    ] {
        if f.intersects(have) {
            flags |= bit as u32;
        }
    }
    (encode_color(cell.fg), encode_color(cell.bg), flags)
}

fn encode_color(c: Color) -> u32 {
    match c {
        Color::Spec(rgb) => color::rgb(rgb.r, rgb.g, rgb.b),
        Color::Indexed(i) => color::indexed(i),
        Color::Named(n) => {
            let v = n as usize;
            if v < 16 {
                color::indexed(v as u8)
            } else if (NamedColor::DimBlack as usize..=NamedColor::DimWhite as usize).contains(&v) {
                // Dim variants map to their base color; the renderer dims.
                color::indexed((v - NamedColor::DimBlack as usize) as u8)
            } else {
                // Foreground, Background, Cursor and the bright/dim
                // foreground variants: the renderer's defaults.
                color::DEFAULT
            }
        }
    }
}

/// Titles are remote-controlled text: no control characters, bounded length.
fn sanitize_title(title: &str) -> String {
    title
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_TITLE_CHARS)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The text of a frame row, with blanks for skipped columns.
    fn row_text(row: &v1::Row) -> String {
        let mut s = String::new();
        for run in &row.runs {
            while (s.chars().count() as u32) < run.col {
                s.push(' ');
            }
            s.push_str(&run.text);
        }
        s
    }

    fn screen(frame: &v1::Frame) -> Vec<String> {
        frame.rows.iter().map(row_text).collect()
    }

    #[test]
    fn first_frame_is_full_and_holds_the_text() {
        let mut t = Terminal::new(20, 4);
        t.feed(b"hello\r\nworld");
        let f = t.frame().expect("frame");
        assert!(f.full);
        assert_eq!(f.rows.len(), 4);
        assert_eq!(screen(&f)[..2], ["hello", "world"]);
        let c = f.cursor.unwrap();
        assert_eq!((c.row, c.col, c.visible), (1, 5, true));
        assert_eq!(f.seq, 1);
    }

    #[test]
    fn later_frames_carry_only_changed_rows() {
        let mut t = Terminal::new(20, 4);
        t.feed(b"one\r\ntwo");
        t.frame();
        assert!(t.frame().is_none(), "no change, no frame");
        t.feed(b"\x1b[1;1Hzzz");
        let f = t.frame().expect("frame");
        assert!(!f.full);
        assert_eq!(f.rows.len(), 1);
        assert_eq!(f.rows[0].index, 0);
        assert_eq!(row_text(&f.rows[0]), "zzz");
    }

    #[test]
    fn colors_and_attributes() {
        let mut t = Terminal::new(20, 2);
        t.feed(b"\x1b[1;31mred\x1b[0m \x1b[38;2;1;2;3mrgb");
        let f = t.frame().unwrap();
        let runs = &f.rows[0].runs;
        assert_eq!(runs[0].text, "red");
        assert_eq!(runs[0].fg, color::indexed(1));
        assert_eq!(
            runs[0].flags & v1::RunFlag::Bold as u32,
            v1::RunFlag::Bold as u32
        );
        let rgb = runs.iter().find(|r| r.text == "rgb").unwrap();
        assert_eq!(rgb.fg, color::rgb(1, 2, 3));
    }

    #[test]
    fn wide_characters_take_two_cells() {
        let mut t = Terminal::new(20, 2);
        t.feed("a漢字b".as_bytes());
        let f = t.frame().unwrap();
        let runs = &f.rows[0].runs;
        let wide = runs
            .iter()
            .find(|r| r.flags & v1::RunFlag::Wide as u32 != 0)
            .unwrap();
        assert_eq!((wide.col, wide.cells, wide.text.as_str()), (1, 4, "漢字"));
        let b = runs.iter().find(|r| r.text.ends_with('b')).unwrap();
        assert_eq!(b.col + b.cells - 1, 5);
    }

    #[test]
    fn device_status_query_gets_a_reply() {
        let mut t = Terminal::new(20, 4);
        let events = t.feed(b"\x1b[6n");
        assert_eq!(events, vec![TermEvent::Reply(b"\x1b[1;1R".to_vec())]);
    }

    #[test]
    fn osc52_copy_becomes_an_event_and_reads_are_refused() {
        let mut t = Terminal::new(20, 4);
        // "hello" base64-encoded, sent to the clipboard.
        let events = t.feed(b"\x1b]52;c;aGVsbG8=\x07");
        assert_eq!(
            events,
            vec![TermEvent::Clipboard {
                text: "hello".into(),
                primary: false
            }]
        );
        // A request to read the clipboard gets no answer.
        assert!(t.feed(b"\x1b]52;c;?\x07").is_empty());
    }

    #[test]
    fn title_is_sanitized() {
        let mut t = Terminal::new(20, 4);
        t.feed("\x1b]0;evil\u{9b}title\x07".as_bytes());
        assert_eq!(t.frame().unwrap().title, "eviltitle");
    }

    #[test]
    fn bell() {
        let mut t = Terminal::new(20, 4);
        assert_eq!(t.feed(b"\x07"), vec![TermEvent::Bell]);
    }

    #[test]
    fn resize_forces_a_full_frame() {
        let mut t = Terminal::new(20, 4);
        t.frame();
        t.resize(30, 6);
        let f = t.frame().unwrap();
        assert!(f.full);
        assert_eq!(f.size, Some(v1::TermSize { cols: 30, rows: 6 }));
        assert_eq!(f.rows.len(), 6);
    }

    #[test]
    fn scrollback() {
        let mut t = Terminal::new(10, 3);
        for i in 0..10 {
            t.feed(format!("line{i}\r\n").as_bytes());
        }
        t.frame();
        t.scroll(2);
        let f = t.frame().unwrap();
        assert_eq!(f.scroll_offset, 2);
        assert!(f.history_lines >= 2);
        assert_eq!(screen(&f)[0], "line6");
        assert!(!f.cursor.unwrap().visible || f.cursor.unwrap().row < 3);
        t.scroll_to_bottom();
        assert_eq!(t.frame().unwrap().scroll_offset, 0);
    }

    /// A small xorshift generator: deterministic, no dependency.
    struct Rng(u64);

    impl Rng {
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }

        fn below(&mut self, n: u64) -> u64 {
            self.next() % n
        }
    }

    /// Pieces of real terminal traffic, to build escape-heavy input from.
    const VOCAB: &[&str] = &[
        "a",
        "hello world ",
        "\r\n",
        "\n",
        "\t",
        "\x08",
        "\x07",
        "漢字",
        "e\u{301}",
        "👍🏽",
        "🇹🇷",
        "\u{200d}",
        "\u{fe0f}",
        "ｗｉｄｅ",
        "\x1b[1;31m",
        "\x1b[0m",
        "\x1b[38;5;208m",
        "\x1b[48;2;10;20;30m",
        "\x1b[7m",
        "\x1b[4:3m",
        "\x1b[H",
        "\x1b[2J",
        "\x1b[K",
        "\x1b[1K",
        "\x1b[5;10H",
        "\x1b[999;999H",
        "\x1b[A",
        "\x1b[10B",
        "\x1b[3C",
        "\x1b[200D",
        "\x1b[2;5r",
        "\x1b[r",
        "\x1b[S",
        "\x1b[3T",
        "\x1bM",
        "\x1bD",
        "\x1bE",
        "\x1b[4h",
        "\x1b[4l",
        "\x1b[?25l",
        "\x1b[?25h",
        "\x1b[?1049h",
        "\x1b[?1049l",
        "\x1b[?1h",
        "\x1b[?2004h",
        "\x1b[?1000h",
        "\x1b[?1006h",
        "\x1b[2 q",
        "\x1b[6 q",
        "\x1b[@",
        "\x1b[5P",
        "\x1b[3L",
        "\x1b[2M",
        "\x1b[3X",
        "\x1b[6n",
        "\x1b[c",
        "\x1b]0;title\x07",
        "\x1b]2;\u{9b}x\x1b\\",
        "\x1b]52;c;aGk=\x07",
        "\x1b]52;c;?\x07",
        "\x1b]8;;http://x\x07link\x1b]8;;\x07",
        "\x1bc",
        "\x1b(0lqk\x1b(B",
        "\x1b#8",
        "\x1b[?7l",
        "\x1b[?7h",
        "\x1b[3g",
        "\x1bH",
        "\x1b[?6h",
        "\x1b[?6l",
        "\x1b[",
        "\x1b]",
        "\x1bP",
        "\u{9b}31m",
        "\x00",
        "\x7f",
        "\u{ffff}",
    ];

    /// Feeds random and escape-heavy input at random sizes, with resizes and
    /// scrolling; every frame must satisfy the render contract.
    #[test]
    fn random_input_always_yields_valid_frames() {
        let mut rng = Rng(0x2545_f491_4f6c_dd1d);
        for _ in 0..300 {
            let mut cols = 2 + rng.below(120) as u16;
            let mut rows = 2 + rng.below(50) as u16;
            let mut t = Terminal::new(cols, rows);
            let mut prev: Option<(u32, u32)> = None;
            for _ in 0..(1 + rng.below(6)) {
                let mut input = Vec::new();
                for _ in 0..rng.below(400) {
                    if rng.below(4) == 0 {
                        input.push(rng.next() as u8);
                    } else {
                        let piece = VOCAB[rng.below(VOCAB.len() as u64) as usize];
                        input.extend_from_slice(piece.as_bytes());
                    }
                }
                t.feed(&input);
                match rng.below(5) {
                    0 => {
                        cols = 2 + rng.below(200) as u16;
                        rows = 2 + rng.below(80) as u16;
                        t.resize(cols, rows);
                    }
                    1 => t.scroll(rng.below(40) as i32 - 20),
                    2 => t.scroll_to_bottom(),
                    3 => t.request_full(),
                    _ => {}
                }
                if let Some(f) = t.frame() {
                    if let Err(e) = veil_protocol::check_frame(&f, prev) {
                        panic!(
                            "invalid frame after {} bytes at {cols}x{rows}: {e}",
                            input.len()
                        );
                    }
                    let size = f.size.unwrap();
                    prev = Some((size.cols, size.rows));
                }
            }
        }
    }

    #[test]
    fn tabs_render_as_blanks_not_control_characters() {
        let mut t = Terminal::new(20, 2);
        t.feed(b"a\tb");
        let f = t.frame().unwrap();
        veil_protocol::check_frame(&f, None).unwrap();
        let text: String = f.rows[0].runs.iter().map(|r| r.text.as_str()).collect();
        assert!(!text.contains('\t'), "{text:?}");
    }

    #[test]
    fn garbage_does_not_panic() {
        let mut t = Terminal::new(80, 24);
        let mut x: u32 = 1;
        let mut buf = Vec::new();
        for _ in 0..100_000 {
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            buf.push(x as u8);
        }
        t.feed(&buf);
        t.frame().unwrap();
    }
}
