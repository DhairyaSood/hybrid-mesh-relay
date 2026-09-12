# Hybrid Mesh Relay — UI Concept

**The one-line pitch:** a calm, dark, precision-instrument feel — like a well-made messaging app that happens to also show you the network underneath it. Not a hacker terminal, not an SOS panel.

Reference points worth knowing: apps like **Bridgefy** and **Briar** solve the same problem (offline/mesh messaging) but look like stripped-down chat apps with almost no personality — they don't lean into the "network" feeling at all. **Meshtastic**'s companion app goes the other way — very node/graph heavy, feels like a ham-radio config tool. The sweet spot for Hybrid Mesh Relay is between them: everyday messaging app warmth, with a thin layer of "network instrument" polish on top — closer to how a modern flight-tracking app or a Vercel/Linear dashboard treats live status.

---

## 1. Visual Identity

- **Background:** near-black charcoal, not pure black — keeps it from feeling like an OLED battery-saver hack.
- **Surfaces:** one step lighter gray for cards, another step up for "elevated" things like modals or the active tab. Thin 1px borders instead of shadows — shadows read as "generic Material," borders read as "engineered."
- **One accent color, used sparingly** — a cool cyan or teal. It shows up on: the active nav icon, links, the "connected" state, and small live indicators. It should almost never appear as a big block of color — more like a highlight pen than a paint bucket.
- **Emergency is a costume, not a skin.** Red only appears on the Emergency priority chip and on an emergency message's own bubble. The rest of the app — nav bar, home screen, message list — stays neutral even if someone has an emergency message queued. This is the single most important restraint in the whole design: if red starts leaking into chrome, it stops meaning anything.
- **Motifs, used lightly:** a faint dot-grid or hexagon-mesh texture behind hero sections (like the top of Home), thin "connector line" dividers instead of thick rules, small radiating-node icon as the app mark.

## 2. Type Personality

Two typefaces doing two different jobs:

- **A geometric technical sans** (Space Grotesk–style) for the app name, screen titles, and section headers. Gives the "engineered product" feeling without tipping into sci-fi.
- **A humanist, highly-legible sans** (Inter–style) for everything you actually read — messages, buttons, settings, descriptions. This is what keeps the app feeling *usable* rather than *cool*.
- **A monospace face** (JetBrains Mono–style) reserved *only* for things that are literally data: node IDs, packet counts, signal percentages, protocol versions. Monospace is the visual signal for "this is a real number from the system," so it should never be used for regular UI copy — that's what makes it feel meaningful instead of decorative.

## 3. App Shell & Navigation

Bottom navigation, five tabs, icon + label, active tab picked out only by the accent color (not a filled pill or background):

**Home · Messages · Network · Devices · Diagnostics**

A slim status strip can live just above the nav bar or in the top bar — a tiny colored dot + one word (Connected / Degraded / Offline) that's visible from every screen, so the user never has to go hunting to know if they're online. This is the one piece of "ambient awareness" that should be everywhere; everything else stays local to its screen.

## 4. Home — the dashboard, not a panic screen

Think of this as "the front page of a weather app," not "mission control." Top to bottom:

1. **Header:** app name in the technical font, small subtitle like "Resilient communication network."
2. **Status card:** one glanceable card — overall state pill (Connected/Degraded/Offline), then four quiet rows: Internet, Nearby Devices, Relay Nodes, Mesh. Numbers in mono, labels in the readable font.
3. **Mini mesh visualization:** a small, calm node diagram — your device as a solid dot in the corner, lines reaching out to 2–4 other dots representing nearby peers/relays. This is the one place the "network" feeling gets to show off visually. Keep it small and quiet here; it earns a bigger, more detailed version on the Network screen.
4. **Recent messages:** 2–3 conversation rows, exactly like a normal chat app preview — sender, snippet, timestamp, a tiny transport icon (Bluetooth/relay/etc.) and delivery word in small caps underneath.
5. **One clear "New Message" button** — solid accent color, the single most prominent tappable thing on the screen. This deliberately anchors the whole app around *talking to people*, with the network status as supporting context, not the headline.

## 5. Messages — feels instantly familiar

A standard conversation list, full stop. Sender name, last message, time, and *very* understated technical detail (a small icon for how it was sent, a small word for its state — Queued/Sending/Relaying/Delivered/Failed). A colored sliver on the left edge of a row can indicate priority (thin neutral line for Normal, thin accent line for Priority, thin red line for Emergency) without needing a big badge.

Inside a conversation: normal chat bubbles, your messages on the right, theirs on the left. Each bubble gets one small caption line beneath it — delivery state + transport — in caption-sized type, so it's there when you look for it and invisible when you don't.

## 6. Compose — where priority lives

A clean composer: recipient picker, text field, and a **three-segment control** for priority (Normal / Priority / Emergency), Normal selected by default. Only when someone deliberately taps "Emergency" does that segment turn red and the send button pick up the same red — everything else in the composer, and the rest of the app, stays exactly as it was. That containment is what keeps this from ever reading as "the SOS app."

## 7. Network — the technical showcase screen

This is allowed to be the most instrument-like screen, since someone who taps into it *wants* the detail. A grid or stacked set of small cards: Internet, Bluetooth, Nearby Devices, Relay Nodes, Active Transport, Connection Quality (a simple bar or arc), then a row of mono-type counters for Packets Sent / Received / Queued. A bigger, more detailed version of the mesh diagram from Home sits at the top — this time it's fine for it to feel a little more "engineering diagram," with subtle pulse animation along an active link to show live activity.

## 8. Devices / Relays — a clean roster

A simple list, one card per device: name, a small status dot, connection state (Connected / Available / "2 hops away"), transport icons (BLE, LoRa, etc.), and signal bars when known. Relay nodes get a small "RELAY" tag so they read as infrastructure rather than a person. No clutter — this should feel like a contacts list that happens to know some technical facts about each entry.

## 9. Diagnostics — the engineer's screen

The one place monospace type gets to dominate a little more — but still organized into clean labeled rows/cards, not a raw log dump. Bluetooth/Wi-Fi/Mesh toggAle states, packet counters, last relay contact time, node ID, protocol version. This screen exists for troubleshooting and trust ("the system is actually doing something"), not for daily use — so it can feel quieter and more technical without hurting the rest of the app's warmth.

## 10. Motion — small and purposeful

- A gentle pulse on the mesh diagram's active connections (not a constant glow everywhere).
- A brief, satisfying transition when a message's delivery state changes (Queued → Delivered), so progress feels tangible.
- Standard, quick screen transitions — nothing bouncy or playful, which would undercut the "reliable instrument" feeling.

---

## The one sentence to keep taping to the wall

**Emergency is a mode inside a communication app, not the identity of the app.** Every design decision above protects that — color discipline, nav structure, and putting "New Message" (not "SOS") as the one big button on Home.

Happy to turn any single screen into an actual visual mockup next, or go back to building it out in Compose once the direction feels right.