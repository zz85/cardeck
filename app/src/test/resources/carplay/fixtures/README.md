# Per-vehicle replay fixtures

Extracted from real run logs by `tools/extract_car_fixtures.py` (`*.jsonl`), used by
`replay/BtLinkReplayTest` and `replay/TunnelReplayTest` for cross-vehicle regression: after changing
the protocol for one vehicle, the recorded stimuli of every other vehicle must produce responses identical to the recording.

```bash
python3 tools/extract_car_fixtures.py ~/logs/cardeck -o app/src/test/resources/carplay/fixtures
./gradlew testStandardDebugUnitTest --tests '*Replay*'
```

**`*.jsonl` is gitignored by default**: it contains real data such as head unit Bluetooth addresses, hotspot names and pairing public keys. To check one in
as a public regression sample, review and sanitize each file individually (SSIDs may be pseudonymized; do not change MACs, they take part in protocol validation) before un-ignoring it.
With no fixtures present, both tests are skipped automatically (fresh clones are unaffected).

## Assertion levels (determined automatically at extraction time, written to each round's rxHasIdent field)

- The old RAW dump budget of 512B per direction often cut identification frames in half (Audi 575B, Geely 489B); more insidiously,
  truncation **stitches the tails of different reads into fake frames whose "length happens to be complete"** (payload checksum fails and the engine silently
  drops them — discovered while debugging replays). The extractor validates frame by frame using the same checks as the engine (header/payload sums to zero),
  trims everything from the first bad frame onward and marks `rxHasIdent`; such rounds only get **link-layer assertions** (SYN / adopted session table /
  identification start 0x1D00 must match the recording);
- Identification frame survives intact (`rxHasIdent=true`) → **full assertions**: the recorded distinct CSM sequence must be
  an ordered prefix of the replay;
- Tunnel layer: the recorded sequence must be an **ordered subsequence** of the replay (allows deliberately added responses, such as the MIB3 burst,
  while catching missing/out-of-order responses);
- The budget has been raised to 4096B (`Iap2LinkChannel.RAW_DUMP_LIMIT*`) — new logs can record identification/MFi/credentials
  in full, and fixtures are automatically upgraded to full assertions.
