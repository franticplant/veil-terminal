//! Remote terminal output: the first bytes choose the size and the resize and
//! scroll steps, the rest is fed as remote output. Every frame must satisfy the
//! render contract the frontends enforce.
#![no_main]

use libfuzzer_sys::fuzz_target;
use veil_protocol::check_frame;
use veil_term::Terminal;

fuzz_target!(|data: &[u8]| {
    if data.len() < 4 {
        return;
    }
    let cols = 2 + (data[0] as u16 % 150);
    let rows = 2 + (data[1] as u16 % 60);
    let mut t = Terminal::new(cols, rows);
    let mut prev = None;
    for chunk in data[4..].chunks(1 + data[2] as usize) {
        t.feed(chunk);
        match chunk[0] % 7 {
            0 => t.resize(2 + (chunk[0] as u16 % 150), 2 + (data[3] as u16 % 60)),
            1 => t.scroll(chunk[0] as i32 - 128),
            2 => t.scroll_to_bottom(),
            _ => {}
        }
        if let Some(f) = t.frame() {
            check_frame(&f, prev).unwrap();
            let s = f.size.unwrap();
            prev = Some((s.cols, s.rows));
        }
    }
});
