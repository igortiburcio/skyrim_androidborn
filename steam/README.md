# `:steam` — Steam client protocol

Pure-Java subset of [SteamKit2](https://github.com/SteamRE/SteamKit) used by
the launcher to log in with a QR code and download the 2011 Skyrim depots.
It has no Android dependencies; the app provides the UI, token storage and
the foreground service.

## License

Ported files are LGPL-2.1 (see `LICENSE-LGPL-2.1.txt`). The `.proto` files are
public domain (`LICENSE-PROTOBUFS`). The rest of the project stays MIT.

## Generated protobuf classes

`src/main/java/steam/proto/*.java` is checked in so the Android build does not
need protoc. To regenerate (protoc 25.5 + protobuf-java 3.25.5):

```sh
mkdir -p /tmp/p/steam /tmp/p/java
for f in steammessages_auth.steamclient.proto \
         steammessages_clientserver_login.proto \
         steammessages_base.proto \
         steammessages_unified_base.steamclient.proto \
         enums.proto; do
    { echo 'option java_package = "steam.proto";'; cat "$f"; } \
        > /tmp/p/steam/"$f"
done
protoc -I/tmp/p/steam -I<protoc>/include --java_out=/tmp/p/java /tmp/p/steam/*.proto
cp /tmp/p/java/steam/proto/*.java src/main/java/steam/proto/
```

The `java_package` line is a local addition; SteamKit2 generates C# from the
same files. Keep the Valve headers intact.

## Host smoke test

`./gradlew :steam:test` runs the packet tests. The QR smoke test that talks to
Steam is opt-in:

```sh
STEAM_SMOKE=1 ./gradlew :steam:test --tests '*QrSmokeTest*' --info
```

It prints the `https://s.team/q/...` challenge URL. Rendering the QR and the
UI live in the app.
