// Generates the Rust types for proto/veil/engine/v1/engine.proto. protox
// parses the schema in Rust, so the build needs no protoc binary.

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = "../../proto";
    let file = "../../proto/veil/engine/v1/engine.proto";
    println!("cargo:rerun-if-changed={file}");
    let descriptors = protox::compile([file], [root])?;
    prost_build::Config::new().compile_fds(descriptors)?;
    Ok(())
}
