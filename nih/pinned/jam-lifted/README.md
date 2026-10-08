# Jam Lifted API

Unmodified `jam.vm.Lifted` from [ekmett/jam](https://github.com/ekmett/jam/blob/92a0cbcda6b9413dc2df3b2a6e327f06d90ea94b/vm/src/bridge/java/jam/vm/Lifted.java), commit `92a0cbcda6b9413dc2df3b2a6e327f06d90ea94b`. Source SHA-256: `35ec4fb68cccf8a13d8c856ffa1dd5e0b1d0d8c53212e5a1bcb9dfdc6476d163`. Its original dual license is preserved in the source and [license text](../../licenses/jam-LICENSE.txt).

The macOS and Windows Jam pins predate this ordinary Java interface; the Linux release includes the same source. Gradle compiles this source with THC; it does not duplicate `jam.vm.Weak`, replace the native bridge or modify the VM. The Jam maintainer supports this supplemental API on the existing provider. Remove this source input when every pinned platform supplies the interface.
