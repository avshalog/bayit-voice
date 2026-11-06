#!/usr/bin/env node
// A pretend Bayit Voice device for testing multi-device arbitration (SPEC section 6.2) with one phone.
//
//   node tools/arbitration_peer.js listen <group-id>
//       Prints every valid claim seen on the network for that group.
//   node tools/arbitration_peer.js claim <group-id> <score> [name]
//       Sends one claim, repeated for 400 ms like a real device, then prints the claims it heard.
//
// <group-id> is what the app derives from its settings: "ha:" + the normalized HA address
// (e.g. "ha:http://192.168.1.10:8123"), or "name:" + the lower-cased group name if one is set.
// Run it on a computer on the same Wi-Fi network as the phone.

const dgram = require("dgram");
const crypto = require("crypto");

const GROUP = "239.255.66.77";
const PORT = 47661;
const MAGIC = 0x42563101;
const MAX_NAME = 40;

const groupKey = (groupId) => crypto.createHash("sha256").update(`bayitvoice-arbitration|${groupId}`, "utf8").digest();

function encode(key, { deviceId, name, score, timestampMs }) {
  let nameBytes = Buffer.from(name, "utf8");
  if (nameBytes.length > MAX_NAME) nameBytes = nameBytes.subarray(0, MAX_NAME);
  const body = Buffer.alloc(25 + nameBytes.length);
  body.writeInt32BE(MAGIC | 0, 0);
  body.writeBigInt64BE(BigInt(deviceId), 4);
  body.writeBigInt64BE(BigInt(timestampMs), 12);
  body.writeFloatBE(score, 20);
  body.writeUInt8(nameBytes.length, 24);
  nameBytes.copy(body, 25);
  return Buffer.concat([body, crypto.createHmac("sha256", key).update(body).digest()]);
}

function decode(key, msg) {
  if (msg.length < 25 + 32) return null;
  const body = msg.subarray(0, msg.length - 32);
  const mac = crypto.createHmac("sha256", key).update(body).digest();
  if (!crypto.timingSafeEqual(mac, msg.subarray(msg.length - 32))) return null;
  if (body.readInt32BE(0) !== (MAGIC | 0)) return null;
  const nameLength = body.readUInt8(24);
  if (nameLength !== body.length - 25) return null;
  return {
    deviceId: body.readBigInt64BE(4).toString(),
    timestampMs: Number(body.readBigInt64BE(12)),
    score: body.readFloatBE(20),
    name: body.subarray(25).toString("utf8"),
  };
}

function open(key, onClaim, ready) {
  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  socket.on("message", (msg, from) => {
    const claim = decode(key, msg);
    if (claim) onClaim(claim, from.address);
  });
  socket.bind(PORT, () => {
    socket.addMembership(GROUP);
    socket.setMulticastTTL(1);
    ready(socket);
  });
  return socket;
}

const [mode, groupId, scoreArg, nameArg] = process.argv.slice(2);
if (!["listen", "claim"].includes(mode) || !groupId) {
  console.error("usage: node arbitration_peer.js listen <group-id> | claim <group-id> <score> [name]");
  process.exit(1);
}
const key = groupKey(groupId);

if (mode === "listen") {
  open(key, (c, ip) => console.log(new Date().toISOString(), ip, c.name, "score", c.score.toFixed(1), "age", Date.now() - c.timestampMs, "ms"), () =>
    console.log(`listening on ${GROUP}:${PORT} for group ${groupId}`));
} else {
  const mine = { deviceId: 1 + Math.floor(Math.random() * 1e12), name: nameArg || "test peer", score: Number(scoreArg) };
  const heard = new Map();
  open(key, (c) => { if (c.deviceId !== String(mine.deviceId)) heard.set(c.deviceId, c); }, (socket) => {
    const send = () => socket.send(encode(key, { ...mine, timestampMs: Date.now() }), PORT, GROUP);
    send();
    const timer = setInterval(send, 80);
    setTimeout(() => {
      clearInterval(timer);
      send();
      const others = [...heard.values()];
      console.log(`sent score ${mine.score}; heard: ${others.length ? others.map((c) => `${c.name} ${c.score.toFixed(1)}`).join(", ") : "nobody"}`);
      setTimeout(() => socket.close(), 50);
    }, 400);
  });
}

module.exports = { encode, decode, groupKey };
