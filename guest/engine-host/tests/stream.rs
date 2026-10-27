//! The engine served over a real stream (a Unix socket pair): the same
//! protocol bytes as in-process, now framed.

use std::os::unix::net::UnixStream;
use std::time::Duration;

use veil_engine::EngineConfig;
use veil_protocol::v1::{self, client_msg, engine_msg};
use veil_protocol::{PROTOCOL_VERSION, decode_engine, encode};
use veil_wire::{read_frame, write_frame};

fn call(sock: &mut UnixStream, id: u64, body: client_msg::Body) -> v1::EngineMsg {
    write_frame(
        sock,
        &encode(&v1::ClientMsg {
            request_id: id,
            body: Some(body),
        }),
    )
    .unwrap();
    loop {
        let frame = read_frame(sock).unwrap().expect("a reply");
        let msg = decode_engine(&frame).unwrap();
        if msg.request_id == id {
            return msg;
        }
    }
}

#[test]
fn protocol_over_a_socket() {
    let (mut ours, theirs) = UnixStream::pair().unwrap();
    ours.set_read_timeout(Some(Duration::from_secs(10)))
        .unwrap();
    let reader = theirs.try_clone().unwrap();
    let host = std::thread::spawn(move || {
        veil_engine_host::serve(
            EngineConfig {
                version: "test".into(),
                data_dir: None,
                dialer: None,
                host_keys: None,
            },
            reader,
            theirs,
        )
    });

    let hello = call(
        &mut ours,
        1,
        client_msg::Body::Hello(v1::Hello {
            protocol_version: PROTOCOL_VERSION,
            client_name: "stream test".into(),
        }),
    );
    assert!(
        matches!(hello.body, Some(engine_msg::Body::HelloAck(_))),
        "{hello:?}"
    );
    let pong = call(&mut ours, 2, client_msg::Body::Ping(v1::Ping { nonce: 7 }));
    assert!(matches!(
        pong.body,
        Some(engine_msg::Body::Pong(v1::Pong { nonce: 7, .. }))
    ));
    let stats = call(&mut ours, 3, client_msg::Body::GetStats(v1::GetStats {}));
    assert!(matches!(stats.body, Some(engine_msg::Body::EngineStats(_))));

    // Closing our end ends the host cleanly.
    drop(ours);
    host.join().unwrap().unwrap();
}
