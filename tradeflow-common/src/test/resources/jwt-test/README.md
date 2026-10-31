TEST-ONLY key material for unit and integration tests. Never used by any runtime profile.

* `test-signing-key.pem` signs tokens in tests (TestJwts, user-service tests).
* `public/b7efda908d8310ec.pem` is the matching public key; its key id is the first 16 hex chars of SHA-256(DER public key).
* `untrusted-signing-key.pem` is NOT in the public key ring: tokens signed with it must be rejected.
