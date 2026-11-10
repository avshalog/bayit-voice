#!/usr/bin/env node
// A pretend Bayit Voice device for testing multi-device arbitration (SPEC section 6.2) with one phone.
//
//   node tools/arbitration_peer.js listen <group-id>
//       Prints every valid claim seen on the network for that group.
//   node tools/arbitration_peer.js claim <group-id> <score> [name] [satellite]
//       Sends one claim, repeated for 400 ms like a real device, then prints the claims it heard.
//       A fifth argument "satellite" marks the claim as a satellite's (it gets the 10 dB advantage).
//
//   node tools/arbitration_peer.js handoff <group-id> <score> [delay-ms]
//       Claims as a phone with <score>; if it wins over a satellite, waits [delay-ms] (default 2500)
//       and hands the command to that satellite, as a phone does for a timer command.
//
// <group-id> is what the app derives from its settings: "ha:" + the normalized HA address
// (e.g. "ha:http://192.168.1.10:8123"), or "name:" + the lower-cased group name if one is set.
// Run it on a computer on the same Wi-Fi network as the phone.

const dgram = require("dgram");
const crypto = require("crypto");

const GROUP = "239.255.66.77";
const PORT = 47661;
const MAGIC = 0x42563102; // version 2: a flags byte (1 = satellite) before the name length
const MAX_NAME = 40;

const groupKey = (groupId) => crypto.createHash("sha256").update(`bayitvoice-arbitration|${groupId}`, "utf8").digest();

function encode(key, { deviceId, name, score, timestampMs, satellite }) {
  let nameBytes = Buffer.from(name, "utf8");
  if (nameBytes.length > MAX_NAME) nameBytes = nameBytes.subarray(0, MAX_NAME);
  const body = Buffer.alloc(26 + nameBytes.length);
  body.writeInt32BE(MAGIC | 0, 0);
  body.writeBigInt64BE(BigInt(deviceId), 4);
  body.writeBigInt64BE(BigInt(timestampMs), 12);
  body.writeFloatBE(score, 20);
  body.writeUInt8(satellite ? 1 : 0, 24);
  body.writeUInt8(nameBytes.length, 25);
  nameBytes.copy(body, 26);
  return Buffer.concat([body, crypto.createHmac("sha256", key).update(body).digest()]);
}

// A hand-over (HandOff.kt): magic, from id, to id, timestamp, then the HMAC.
function encodeHandOff(key, fromId, toId) {
  const body = Buffer.alloc(28);
  body.writeInt32BE(0x42564801 | 0, 0);
  body.writeBigInt64BE(BigInt(fromId), 4);
  body.writeBigInt64BE(BigInt(toId), 12);
  body.writeBigInt64BE(BigInt(Date.now()), 20);
  return Buffer.concat([body, crypto.createHmac("sha256", key).update(body).digest()]);
}

function decode(key, msg) {
  if (msg.length < 26 + 32) return null;
  const body = msg.subarray(0, msg.length - 32);
  const mac = crypto.createHmac("sha256", key).update(body).digest();
  if (!crypto.timingSafeEqual(mac, msg.subarray(msg.length - 32))) return null;
  if (body.readInt32BE(0) !== (MAGIC | 0)) return null;
  const nameLength = body.readUInt8(25);
  if (nameLength !== body.length - 26) return null;
  return {
    deviceId: body.readBigInt64BE(4).toString(),
    timestampMs: Number(body.readBigInt64BE(12)),
    score: body.readFloatBE(20),
    satellite: (body.readUInt8(24) & 1) === 1,
    name: body.subarray(26).toString("utf8"),
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

const [mode, groupId, scoreArg, nameArg, kindArg] = process.argv.slice(2);
if (!["listen", "claim", "handoff"].includes(mode) || !groupId) {
  console.error("usage: node arbitration_peer.js listen <group-id> | claim <group-id> <score> [name] [satellite] | handoff <group-id> <score> [delay-ms]");
  process.exit(1);
}
const key = groupKey(groupId);

if (mode === "listen") {
  open(key, (c, ip) => console.log(new Date().toISOString(), ip, c.name, c.satellite ? "(satellite)" : "", "score", c.score.toFixed(1), "age", Date.now() - c.timestampMs, "ms"), () =>
    console.log(`listening on ${GROUP}:${PORT} for group ${groupId}`));
} else {
  const mine = { deviceId: 1 + Math.floor(Math.random() * 1e12), name: mode === "handoff" ? "test phone" : nameArg || "test peer", score: Number(scoreArg), satellite: kindArg === "satellite" };
  const heard = new Map();
  open(key, (c) => { if (c.deviceId !== String(mine.deviceId)) heard.set(c.deviceId, c); }, (socket) => {
    const send = () => socket.send(encode(key, { ...mine, timestampMs: Date.now() }), PORT, GROUP);
    send();
    const timer = setInterval(send, 80);
    setTimeout(() => {
      clearInterval(timer);
      send();
      const others = [...heard.values()];
      console.log(`sent score ${mine.score}; heard: ${others.length ? others.map((c) => `${c.name} ${c.score.toFixed(1)}${c.satellite ? " (satellite)" : ""}`).join(", ") : "nobody"}`);
      const satellite = others.find((c) => c.satellite);
      if (mode !== "handoff" || !satellite) { setTimeout(() => socket.close(), 50); return; }
      setTimeout(() => {
        const message = encodeHandOff(key, mine.deviceId, BigInt(satellite.deviceId));
        const again = setInterval(() => socket.send(message, PORT, GROUP), 80);
        socket.send(message, PORT, GROUP);
        console.log(`handed the command to ${satellite.name}`);
        setTimeout(() => { clearInterval(again); socket.close(); }, 400);
      }, Number(nameArg || 2500));
    }, 400);
  });
}

module.exports = { encode, decode, groupKey };
