# Project notes

## Networking model

The host is authoritative. Player 1 is always the host. Player 2 is always the client.

Fight input from player 2 is sent as a state packet. Race input is sent as discrete tap events. The host publishes a state snapshot around 20 times per second over a reliable ordered stream (TCP or RFCOMM).

## Easy extension points

- Add a new mini-game by extending `GameMode` and adding update/draw/protocol branches in `DuelGameView`.
- Add LAN discovery with UDP broadcast/mDNS while keeping the existing TCP game channel.
- Replace the Canvas vector characters with sprite sheets without changing the network layer.
- Add audio via `SoundPool`.
