# PanelDash — YAML config format (v0.2)

PanelDash is a small Android dashboard app for wall panels (built for the Sonoff
NSPanel Pro: Android 8.1, 480×480 px). It reads one YAML file, renders it, and talks
to Home Assistant over the REST API for state and actions. Edit the file and save —
the app reloads it automatically within ~2 seconds. No restart, no reinstall.

## Pages

The UI is **paginated** — no scrolling. Every page holds one screen, and a bottom
nav bar switches between them. The recommended layout:

- **Page 1 = Quick** — a small set of the most-used controls / status readouts.
- **One page per category** — Lights, Climate, Blinds, etc. (whatever fits your home).

```yaml
pages:
  - name: "Quick"        # bottom nav label
    type: vstack
    padding: 10
    spacing: 8
    children: [...]

  - name: "Lights"
    type: vstack
    children: [...]
```

Notes:
- Page names are the nav bar labels — keep them short (≤ 8 chars renders best).
- With **2–3 pages** the nav bar is comfortable; up to **5** works if labels are short.
- Without `pages:`, a single `root:` element renders (legacy format, no nav bar).

### Fitting a 480×480 page

Budget the screen height: keep the content under ~440 px (nav bar takes ~44 px).
Reference sizes at `font_scale: 1.0`:

| element | height | rows that fit |
|---|---|---|
| button | 56 px | 6 rows |
| toggle | 56 px | 6 rows |
| label (default 16) | ~22 px | — |
| label (size 13–14) | ~19 px | — |

Rough per-row cost: one hstack of buttons = 56 px + spacing. A page like
"Lights: 3 rows of toggles + a scene row + a header" fits fine; if a category
overflows, split it across two pages.

---

## Top-level structure

```yaml
server:    # how to reach Home Assistant + auth
options:   # panel behaviour
theme:     # colors and sizing
header:    # optional info bar, rendered above EVERY page
doorbell:  # optional ring alert: full-screen live camera popup
proximity: # optional wake-on-approach: dim when nobody is near
pages:     # the UI, one entry per page (or single "root:" for legacy)
```

### header — always-visible info bar

One element tree (usually an `hstack` of labels) pinned above all pages —
temperature, humidity, window state, whatever should never be hidden.
It is polled continuously and does not consume page space.

```yaml
header:
  type: hstack
  spacing: 6
  padding: 8
  children:
    - type: label
      text: "Office"
      weight: 1
    - type: label
      format: "{state}\u00b0C"
      entity: sensor.office_temperature
    - type: label
      format: "{state}%"
      entity: sensor.office_humidity
```

### server

```yaml
server:
  url: "http://homeassistant.local:8123"     # HA base URL (trailing slash OK)
  token: "eyJhbGciOi..."               # long-lived access token
  token_file: "/sdcard/paneldash/token.txt"   # optional; if set+readable it wins over `token`
  poll_seconds: 5                      # kept for compatibility; state is now pushed
```

The token comes from Home Assistant: Profile → Security → Long-lived access tokens.

**Live push (v0.9+)**: the panel subscribes to HA's WebSocket API and every state
change (including attributes like `current_position`) is pushed to it within
~100 ms — no polling interval involved. On a dropped connection it reconnects with
backoff. `poll_seconds` only applies to the legacy fallback path.

### doorbell — ring alert with live camera

```yaml
doorbell:
  entity: binary_sensor.doorbell_doorbell_2   # when this turns ON, the popup shows
  camera: camera.front_door                   # camera entity for the live view
  title: "Front door"
  seconds: 30                                 # auto-dismiss (5-300)
```

When the ring entity turns on (or an `event.` entity fires — both work), the panel
interrupts whatever it is showing with a full-screen alert: live camera frames
(refreshed once per second, resized server-side to keep the panel light) and a big
Dismiss button. It auto-dismisses after `seconds`; a second ring restarts the
countdown. Tapping anywhere dismisses it too. Without `camera:` the popup still
shows (title + countdown), just without the video.

`sound: true` plays a short two-note chime on the panel's own speaker when the
alert fires (bundled with the app; no network needed). `volume` (0-1, default 0.8)
scales that chime only — the system volume is left alone.

### proximity — wake on approach

```yaml
proximity:
  enabled: true
  near_value: 2000     # raw proximity reading >= this = someone is close
  near_delta: 10       # ...or reading moved this far from the resting baseline
  away_seconds: 45     # dim after this long with no approach and no touch
  dim_brightness: 0.03 # backlight fraction while dimmed (0.005-1.0)
```

Uses the panel's proximity sensor to dim the backlight to near-black when the room
is empty (preserves backlight life on an always-on device), and wake it the moment
someone approaches or touches the screen. Set `enabled: false` to switch it off.
Both detection models run at once (absolute threshold + baseline delta), so it
works whichever way the hardware reports. The stock NSPanel app uses `near_value:
2000` for its wake-on-wave feature; idle readings here sit at ~1955.

### options

```yaml
options:
  orientation: auto      # auto | portrait | landscape
  daynight: auto         # auto | off  (see below)
  latitude: 51.5074      # optional: pin location, skips the IP lookup
  longitude: -0.1278
  auto_brightness: false # scale screen brightness from the light sensor
  brightness_min: 10     # dimmest backlight (0-255), used when the room is dark
  brightness_max: 255    # brightest backlight (0-255), used when the room is lit
  idle_return_seconds: 0 # back to the first page after N s with no touches (0 = off)
```

**Idle return (`idle_return_seconds`)**: after that many seconds with no touches
anywhere on the panel, it drifts back to the first page (the main screen) — and
closes any open camera view. For a wall panel this means the next person always
finds the home screen, no matter where the last person left it. `900` = 15 minutes.
`0` disables it.

**Auto-brightness (`auto_brightness: true`)**: ties the panel's backlight to the
same ambient light sensor — dim at night, full brightness in daylight, on a
perceptual (sqrt) ramp between `brightness_min` and `brightness_max`. Off by
default; takes effect whenever the light sensor is available.

**Theme switching (`daynight`)**:

- `sensor` (default): reads the panel's **own ambient light sensor**. A lit room
  gets `theme_light`, a dark room `theme_dark` — so the panel matches the actual
  room, whatever the hour. Requires hardware with a light sensor (the NSPanel Pro
  has one); falls back to sun times on hardware without one. Hysteresis
  (`dark_below_lux` / `light_above_lux`) plus an ~8 s debounce keep passing shadows
  from flipping the theme. **Calibrate the two thresholds to the room**: read the
  sensor in the room's dark and lit states, then set the thresholds between them
  (this office: dark evening ~40 lx, spots on ~700 lx; thresholds 60/200). Defaults
  (10/40) suit a genuinely dark room only — a dim evening at 40+ lx would read as
  "lit".
- `auto`: sun times — `theme_light` between sunrise and sunset. Sunrise/sunset are
  computed on-device; the location is resolved once from the public IP and cached
  for 30 days (set `latitude`/`longitude` to skip the lookup).
- `off`: always use the single `theme` block.

### theme

```yaml
theme:
  background: "#0E1116"  # page background
  surface: "#1B222C"     # button / card background
  accent: "#3D8BFD"      # highlight color for "on" elements
  text: "#EAF0F6"        # text color
  radius: 14             # corner radius in px (0–40)
  font_scale: 1.0        # multiplies every font size (0.5–3.0)
```

`theme_light` / `theme_dark` are optional extra blocks with the same keys; when
present, the panel picks between them by sun position (see `daynight` above) and
falls back to `theme` for whichever is missing. The renderer adapts its tile
chrome to the palette polarity automatically — off-tiles read as near-black on a
dark theme and as light cards on a light theme — so one config works in both.

Colors: `#RRGGBB` or `#AARRGGBB`.

---

## Elements

Every page (and every container) holds a tree of elements. Containers hold
`children`; children can nest freely. Element `type`s: `vstack`, `hstack`,
`grid`, `label`, `button`, `toggle`, `progress`, `camera`, `check`, `spacer`.
Common props: `entity`, `action`, `weight`, `padding`, `spacing`.

### vstack — vertical container
```yaml
- type: vstack
  spacing: 12     # gap between children, default 8
  padding: 14     # inner padding
  children: [...] # children with `weight: N` share the leftover height
```

### hstack — horizontal container
```yaml
- type: hstack
  spacing: 12
  children: [...] # children with `weight: N` share the leftover width
```

### grid — big touch-target tiles
```yaml
- type: grid
  columns: 2          # tiles per row (1-4). With 6 tiles: 2 columns = 3 rows,
                      # 3 columns = 2 rows. Each tile ~half screen width (2 cols).
  spacing: 8          # gap between tiles, px
  weight: 1           # fill remaining page height (splits equally between rows)
  # height: 300       # or a fixed pixel height for the whole grid
  # row_height: 110   # or a fixed height for each row
  children: [ ...tiles... ]
```
A grid is the recommended layout for wall panels: **max 6 tiles per page**
so everything is a large one-finger target (on a 480x480 panel a 2-column
tile is roughly 220x110 px — about 5x the 48 dp minimum). Tiles are laid
out row by row; a shorter last row keeps tile widths equal.

Works with any elements, but pairs best with `toggle` / `button` tiles.
Button tiles can show a live value under the label with `sub:` / `sub_entity:`
(see the button section).

### label — text
```yaml
- type: label
  text: "Living room"                  # static text
# or live entity text below — placeholders {state} and {friendly} are substituted:
- type: label
  format: "Door: {state}"
  entity: binary_sensor.example_door
  size: 14                             # font size, default 16
  align: center                        # start | center | end (default start)
  weight: 1                            # optional in a row: share width
  decimals: 1                          # optional: round a numeric state (28.700006 -> 28.7)
```

`decimals: N` rounds numeric states to N decimal places (0–4) — handy for sensors
that report float noise (28.700006). Non-numeric states are left untouched.

### button — tappable element
```yaml
- type: button
  text: "Lights"                       # defaults to the entity's friendly name
  entity: light.living_room
  action: toggle                       # see Actions table below
  weight: 1                            # inside hstack: share the row width
  sub: "Now {state}"                   # optional live sub-line under the label
  sub_entity: cover.blinds             # entity for the sub-line (default: `entity`)
  highlight: false                     # never accent-fill (for action tiles)
```
Buttons render with a raised look — bezel, glossy cap, engraved label, and a
pressed-in state while held. Highlight: the button fills with the accent color when
the entity reads "on" (anything other than off / closed / unavailable / unknown /
none / idle / standby / 0). Use `action: none` for a pure status indicator that
never calls HA (still highlights).

`sub` / `sub_entity` put a live value under the main label — the wall-panel trick
that makes a tile scannable at a glance ("AC cool"). `sub_entity` is required when
the tap target (`service: script.…`) has no state of its own. `highlight: false`
is for action tiles (Open / Close / scene buttons) that should not read as an
on/off switch.

**`color: "#RRGGBB"`** sets the tile's OWN fill color, used when it is lit (and as
the accent on progress gauges). Omit it to inherit the theme accent. Per-tile
colors let you encode meaning: amber = spots, violet = Nanoleaf, blue = desk,
cyan = climate, green = blinds.

### toggle — status tile
```yaml
- type: toggle
  text: "Air conditioning"
  entity: climate.502e912b7113
  action: toggle
  color: "#4CD1E0"                     # optional: this tile's own fill when on
```
A big status tile: label on top, state in huge type, whole tile is the tap target.
**Off = near-black tile; On = full fill in the tile's color** (its own `color:` or
the theme accent), with dark text. Designed for glanceability from across a room.

### progress — value bar
```yaml
- type: progress
  text: "Position"                # label on the left (or entity friendly name)
  entity: cover.blinds
  attribute: current_position     # optional; reads a state attribute instead of the state
  min: 0                          # range start (default 0)
  max: 100                        # range end (default 100)
  format: "{value}%"              # right-side value; {value} is substituted (default "{value}%")
```
Accepts the same per-component `color:` (the bar + value text). Draws a labelled
bar filled proportionally to the value. Works with any numeric
value — entity state (`sensor.…`) or attribute (`current_position` on covers,
`temperature` on climates, `brightness` on lights). Shows `—` when the value is
unavailable. No HA-side template sensors needed.

```yaml
# Examples:
- type: progress
  text: "Blinds"
  entity: cover.blinds
  attribute: current_position
  min: 0
  max: 100
  format: "{value}%"

- type: progress
  text: "AC target"
  entity: climate.office_ac
  attribute: temperature
  min: 18
  max: 30
  format: "{value}°C"

- type: progress
  text: "Brightness"
  entity: light.desk
  attribute: brightness          # raw HA brightness (0-255)
  max: 255
```

### camera — tap for a live view
```yaml
- type: camera
  text: "Doorbell"                       # tile label
  entity: camera.front_door              # camera entity to stream
```
A calm tile (label + VIEW, never fills) that opens a full-screen live view when
tapped: same frame loop as the doorbell alert (1 fps, server-resized). CLOSE bar
or tapping the backdrop returns to the dashboard. Good for a "Cams" page with
2-4 tiles.

### check — health row
```yaml
- type: check
  text: "UPS battery"                    # row label
  entity: sensor.ups_battery
  ok_above: 50                           # healthy while the value is ABOVE this
  format: "{value}%"                     # right-side value (also {state})
```
A compact one-line health row, designed to be quiet: while the condition holds it
renders as a dim, near-black strip. When it trips — value crosses the threshold,
state not in `ok_is`, or the entity goes unavailable/unknown — the row lights up
with a red dot, red value and a faint red fill. Conditions (use one):

- `ok_above: N` / `ok_below: N` — healthy while the numeric value is above/below N.
- `ok_is: "a,b"` — healthy while the state is one of the comma-separated values.

Ideal for a "Health" page: low device batteries, UPS / power status, tamper and
fault flags, connectivity — the things you only want to see when they're wrong.

### spacer — empty space
```yaml
- type: spacer
  height: 20        # fixed px
# or, inside a stack, to absorb leftover space:
- type: spacer
  weight: 1
```
On a paginated layout, prefer fixed `height:` spacers only for small gaps —
weighted spacers are for the legacy single-root layout. On pages, design each
page to fill its screen without overflow instead.

---

## Actions → what gets called

| action | Home Assistant call |
|---|---|
| `none` | nothing (display only) |
| `toggle` (or omitted) | `homeassistant.toggle` |
| `turn_on` / `on` | `homeassistant.turn_on` |
| `turn_off` / `off` | `homeassistant.turn_off` |
| `activate` | `scene.turn_on` |
| `service` | whatever you set in `service:` (e.g. `light.turn_on`, `script.movie_time`) |

For `action: service` you may add extra fields under `data:`:

```yaml
- type: button
  text: "Dim to 30%"
  entity: light.office
  action: service
  service: light.turn_on
  data:
    brightness_pct: 30

# Scripts can be called without an entity:
- type: button
  text: "Movie time"
  action: service
  service: script.movie_time
```

---

## Full example (paginated)

```yaml
server:
  url: "http://homeassistant.local:8123"
  token: "PASTE_LONG_LIVED_TOKEN_HERE"
  poll_seconds: 5

options:
  orientation: auto

theme:
  background: "#0E1116"
  surface: "#1B222C"
  accent: "#3D8BFD"
  text: "#EAF0F6"
  radius: 14
  font_scale: 1.0

pages:
  - name: "Quick"
    type: vstack
    padding: 10
    spacing: 8
    children:
      - type: hstack
        spacing: 6
        children:
          - type: label
            text: "Home"
            weight: 1
          - type: label
            entity: sensor.living_room_temperature
            format: "{state}°C"
      - type: hstack
        spacing: 6
        children:
          - type: toggle
            text: "Lights"
            entity: light.living_room
            action: toggle
            weight: 1
          - type: toggle
            text: "Desk lamp"
            entity: switch.desk_lamp
            action: toggle
            weight: 1
      - type: button
        text: "All off"
        entity: scene.all_off
        action: activate

  - name: "Lights"
    type: vstack
    padding: 10
    spacing: 8
    children:
      - type: hstack
        spacing: 6
        children:
          - type: toggle
            text: "Living"
            entity: light.living_room
            action: toggle
            weight: 1
          - type: toggle
            text: "Kitchen"
            entity: light.kitchen
            action: toggle
            weight: 1
      - type: hstack
        spacing: 6
        children:
          - type: button
            text: "Bright"
            entity: scene.living_bright
            action: activate
            weight: 1
          - type: button
            text: "Dim"
            entity: scene.living_dim
            action: activate
            weight: 1
```

---

## Where the file lives on the panel

First readable location wins:

1. `/sdcard/paneldash/config.yaml`   ← recommended
2. `/sdcard/Android/data/com.mcsoftware.paneldash/files/config.yaml`
3. app-internal copy (auto-created from the bundled sample on first run)

Deploy with adb:
```
adb connect 192.168.1.100:5555
adb push config.yaml /sdcard/paneldash/config.yaml
```

## Panel design constraints (NSPanel Pro)

- Screen 480×480 px, density 160 (1 px = 1 dp). Nav bar ~44 px; content area ~436 px.
- Touch targets ≥56 px tall; design each page to fill one screen without overflow.
- Suggested sizes: page titles 20–24, buttons 18 (default), status labels 13–15.
- Split categories across pages rather than cramming: 2–3 rows per "block",
  2–3 blocks per page.

## Gathering the details from Home Assistant (for config generators)

REST API — base `http://homeassistant.local:8123`, header `Authorization: Bearer <token>`:

- `GET /api/states` — every entity + current state (large; filter by domain:
  `light.`, `switch.`, `binary_sensor.`, `sensor.`, `climate.`, `scene.`, `script.`, `media_player.`)
- `GET /api/states/<entity_id>` — one entity
- `GET /api/services` — all available services (to check what can be called)
- `POST /api/services/<domain>/<service>` — call one, body `{"entity_id": "..."}`
  plus any extra fields

When building a config, collect per control: the real `entity_id`, its friendly
name, its current state (to sanity-check the display logic), and pick the right
action (lights/switches → `toggle`; scenes → `activate`; scripts → `service`;
anything else → explicit `service` + `data`).

## What to produce

One valid `config.yaml` following this schema — ready to push to
`/sdcard/paneldash/config.yaml`. Structure it as:

1. **Quick page** — 4–8 most-used controls + 1–2 status readouts.
2. **One page per category** — everything else, grouped by function (Lights,
   Climate/AC, Blinds, ...). Keep each page within ~436 px of content.
