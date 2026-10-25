# Screen Resizing Multi-Option Selector (480p, 540p, 720p, 1080p)

Add interactive multi-option screen resolution scaling with immediate-apply segmented chips (480p, 540p, 720p, 1080p), auto-scaled to the device's native aspect ratio, removing the previous toggle/go-back button design.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following design decisions were confirmed via interactive clarification:
> - **Resolution Presets**: 480p, 540p, 720p, and 1080p without a separate Reset button.
> - **Aspect Ratio & Density**: Target dimensions and display density are calculated dynamically based on the device's native display metrics (preserving aspect ratio and visual UI scaling).
> - **Workflow**: Remove the separate "Turn ON" / "Turn OFF" / "Go Back" button. Instead, selecting any resolution chip immediately executes the resolution and density adjustment over the ADB session.

---

### 1. Overview & Core Concept

- **What It Does**: Allows users to dynamically change their Android device rendering resolution between 480p, 540p, 720p, and 1080p directly from the app.
- **Target Audience**: Gamers and performance tuners optimizing FPS, thermals, and battery life for demanding games and emulators.
- **Key Value**: Instant, single-tap switching between performance profiles without cumbersome multi-step modal prompts or back buttons.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Device Connection**: User pairs and connects to the local wireless debugging session.
2. **Resolution Selection**: In the "Gaming Console Mode" section, the user sees a sleek horizontal segmented chip row with options: `480p`, `540p`, `720p`, `1080p`.
3. **Immediate Application**: Tapping any chip highlights it, executes `wm size <w>x<h>` and proportional `wm density <dpi>` over the ADB session, streams progress into the log console, and saves the preference.
4. **No Back Button**: The previous binary "Turn ON / Turn OFF" and overlay "Exit" buttons are eliminated in favor of direct profile selection.

#### Visual Hierarchy & Theme
- **Segmented Chips**: Material 3 `SingleChoiceSegmentedButtonRow` or custom elevated tonal filter chips with spring-animated selection indicator.
- **Active State**: Primary container fill with bold typography, showing resolution dimensions (e.g. `720p (720x1600)`) in a sub-caption.
- **Feedback & Feedback Banners**: Real-time progress indicator during command execution and instant confirmation chip state.

---

### 3. Key Product Decisions & Trade-Offs

- **Dynamic Aspect Ratio Calculation**:
  - *Chosen Approach*: Query native display metrics (e.g. 1080x2400, 1440x3120, or 1080x2340). Given a target short edge `p` (480, 540, 720, 1080), compute long edge as `(p * nativeHeight / nativeWidth)` rounded to an even integer. Density is calculated as `(nativeDensity * p / nativeWidth)`.
  - *Why*: Eliminates black bars, letterboxing, or touch offset distortion across different phone and tablet aspect ratios (16:9, 19.5:9, 20:9, 21:9).
- **Direct Apply vs Confirmation Button**:
  - *Chosen Approach*: Tapping a chip immediately applies the resolution.
  - *Why*: Matches user explicit request for "no need for go back button, we just choose between 480p-...-720p-1080p".

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│                   MainActivity UI                      │
│                                                        │
│   [ Game Selector Dropdown ]                           │
│   [ Launch Game Button ]                               │
│                                                        │
│   Resolution Preset Selector:                          │
│   ┌────────┬────────┬────────┬─────────┐               │
│   │  480p  │  540p  │  720p  │  1080p  │ (Segmented)   │
│   └────────┴────────┴────────┴─────────┘               │
│        │                                               │
│        ▼ (Immediate tap callback)                      │
│   applyResolution(preset: ResolutionPreset)            │
└───────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────┐
│                AdbSessionManager                       │
│                                                        │
│   1. Calculate scaled w x h from native aspect ratio   │
│   2. Calculate proportional density                    │
│   3. Execute `wm size <w>x<h>`                         │
│   4. Execute `wm density <dpi>`                        │
│   5. Execute game performance flags (refresh, zen)     │
└────────────────────────────────────────────────────────┘
```

#### Component & State Mapping
- `ResolutionPreset` enum / data class representing `P480 (480)`, `P540 (540)`, `P720 (720)`, and `P1080 (1080)`.
- `selectedResolution`: Reactive Compose state backed by SharedPreferences (`SELECTED_RESOLUTION_P`).
- `AdbSessionManager.applyResolutionPreset(host, port, targetP, nativeWidth, nativeHeight, nativeDensity)`: Asynchronous execution pipeline logging each step to the console.

---

### 5. Implementation Steps

1. **Model & Resolution Calculation**:
   - Define `ResolutionPreset` with labels ("480p", "540p", "720p", "1080p") and target short dimensions (480, 540, 720, 1080).
   - Add helper in `AdbSessionManager` to calculate scaled width, height, and density using the device's native dimensions.
2. **Session Execution Update**:
   - Update `AdbSessionManager` with `applyResolutionProfile(host, port, preset, nativeWidth, nativeHeight, nativeDensity)`.
3. **UI Overhaul in `MainActivity.kt`**:
   - Remove the old "Turn ON" / "Turn OFF" buttons.
   - Add the Segmented Button / Filter Chip selector for `480p`, `540p`, `720p`, and `1080p`.
   - Wire immediate apply on chip selection with execution progress indicators.
4. **Overlay Service Adjustment**:
   - Update `AdbOverlayService` to align with the simplified direct-switching paradigm.
5. **Compilation Verification**:
   - Run `compile_applet` to ensure clean build and type safety.
