//! Decoding untrusted protocol bytes, in both directions: never a panic, and
//! whatever decodes re-encodes to an equal message.
#![no_main]

use libfuzzer_sys::fuzz_target;
use veil_protocol::{decode_client, decode_engine, encode};

fuzz_target!(|data: &[u8]| {
    if let Ok(m) = decode_client(data) {
        assert_eq!(decode_client(&encode(&m)).unwrap(), m);
    }
    if let Ok(m) = decode_engine(data) {
        assert_eq!(decode_engine(&encode(&m)).unwrap(), m);
        if let Some(veil_protocol::v1::engine_msg::Body::Frame(f)) = &m.body {
            // Validation itself must not panic on any frame.
            let _ = veil_protocol::check_frame(f, None);
        }
    }
});
