//! Framing for engine protocol messages on a byte stream.
//!
//! In one process the engine and its frontend exchange whole messages (the
//! JNI pipe). Across a process or VM boundary they share only a stream: a
//! pipe, a Unix socket, or vsock into Microdroid. Each message is then a
//! frame: a 4-byte big-endian length, then that many bytes of an encoded
//! `ClientMsg` or `EngineMsg`. The length is checked against
//! [`veil_protocol::MAX_MESSAGE_BYTES`] before anything is read or allocated,
//! so a peer cannot make the reader allocate more than one maximum message.
//!
//! The Kotlin side (`StreamPipe` in engine-client) implements the same rules.

use std::io::{self, Read, Write};

use veil_protocol::MAX_MESSAGE_BYTES;

/// Writes one frame. Refuses a message over the protocol's limit.
pub fn write_frame(w: &mut impl Write, msg: &[u8]) -> io::Result<()> {
    if msg.len() > MAX_MESSAGE_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "message of {} bytes is over the {MAX_MESSAGE_BYTES}-byte limit",
                msg.len()
            ),
        ));
    }
    w.write_all(&(msg.len() as u32).to_be_bytes())?;
    w.write_all(msg)?;
    w.flush()
}

/// Reads one frame. `Ok(None)` at a clean end of stream (between frames);
/// an error for a stream that ends inside a frame or announces an oversized
/// one.
pub fn read_frame(r: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_be_bytes(len) as usize;
    if len > MAX_MESSAGE_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame of {len} bytes is over the {MAX_MESSAGE_BYTES}-byte limit"),
        ));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)?;
    Ok(Some(buf))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_and_clean_end() {
        let mut buf = Vec::new();
        write_frame(&mut buf, b"hello").unwrap();
        write_frame(&mut buf, b"").unwrap();
        let mut r = &buf[..];
        assert_eq!(read_frame(&mut r).unwrap().unwrap(), b"hello");
        assert_eq!(read_frame(&mut r).unwrap().unwrap(), b"");
        assert!(read_frame(&mut r).unwrap().is_none());
    }

    #[test]
    fn refuses_oversized_before_allocating() {
        let mut r = &((MAX_MESSAGE_BYTES as u32 + 1).to_be_bytes())[..];
        assert_eq!(
            read_frame(&mut r).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
        let big = vec![0u8; MAX_MESSAGE_BYTES + 1];
        assert!(write_frame(&mut Vec::new(), &big).is_err());
    }

    #[test]
    fn truncated_frame_is_an_error() {
        let mut buf = Vec::new();
        write_frame(&mut buf, b"hello").unwrap();
        let mut r = &buf[..6];
        assert_eq!(
            read_frame(&mut r).unwrap_err().kind(),
            io::ErrorKind::UnexpectedEof
        );
    }
}
