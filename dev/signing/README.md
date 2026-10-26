# Veil signing certificate

`veil-release-cert.pem` is the public certificate of the key that signs every Veil APK, dev and
release alike. The private key never lives in this repository.

SHA-256 of the certificate (DER), as Android's `apksigner verify --print-certs` reports it:

```
95800a53fa01062975d1f7713162c135491dae3b0265a6f2c31c0cdf6369de04
```

This digest is pinned: the dev agent refuses to install an APK signed by anything else (this
matters for a first install, where Android has no earlier signer to compare against), and the
release check verifies built APKs against it.
