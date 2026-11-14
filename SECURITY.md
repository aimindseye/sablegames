# Security policy

Report a security problem privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. Please don't open a public issue for it.

Things we treat as security bugs:

- A Lua game for Sable Games Console that gets out of the sandbox: reads or writes files, reaches the network, runs Java or native code, or reads another game's data.
- A Lua game that hangs or crashes the phone instead of being stopped with a message.
- A release APK whose SHA-256 doesn't match `SHA256SUMS.txt` on its release page, or that isn't signed with the Sable Games release key.

The games ask for no Android permissions; one that does is a bug.
