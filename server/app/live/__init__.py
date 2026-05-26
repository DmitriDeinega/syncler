"""V3 #14 live-channel package.

Splits ephemeral pub/sub (presence, cursors, typing) from
ordered/durable delivery up front so the V3 #17 Redis Streams
swap doesn't force a contract break — see
`docs/live-channel.md` "Frame ordering + delivery semantics".
"""
