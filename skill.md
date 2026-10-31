# Master Skill: Professional Output Formatting, Bidi Protection & UI/UX Governance

## 1. Primary Output Language Rule (Arabic Communication Constraint)
- **Mandatory Response Language:** Always deliver and render all final responses, explanations, chat content, and contextual outputs in **pure, clear, and professional Arabic**, even if the prompt or input message sent by the user is written in English.
- **Arabic Quality Standard:** Use Modern Standard Arabic (فصحى) only. Avoid colloquial dialects, transliterated Arabic (Arabizi), broken characters, or garbled text. Every Arabic sentence must be grammatically correct, spell-checked, and easy to understand. Do not output placeholder symbols (e.g., `�`), empty brackets, or half-translated phrases.
- **Clarity & Understandability:** Prefer short, direct sentences. Define any technical term at first use in Arabic, then keep the English term in backticks. Ensure the user can grasp the meaning, intent, and required action without guessing.

## 2. Bidirectional Text (Bidi), Language Isolation & UI Overflow Prevention (English Technical Rule)
- **Bidi Isolation:** Isolate all English technical terms, variables, functions, and library names inside inline code backticks (e.g., `` `code` ``) or code blocks to prevent RTL/LTR rendering distortion.
- **Arabic + English Mixing:** Never run Arabic and English together without separation. Always wrap English tokens in backticks or place them on their own line so Arabic reading flow is not broken.
- **Responsive UI & Text Fitting:** Prevent text clipping, overlapping, or hidden text using dynamic font scaling, proper text wrapping, or scrolling indicators.
- **Fit-to-Content Density (B16 Governance Rule):** Message bubbles, chat tabs, list rows, window panes, and option tiles must hug their text content exactly — no unjustified whitespace, no oversized fixed containers. Apply consistent compact sizing across ALL controller windows, lists, and tabs so more items fit per screen for professional evidence-grade preview.

## 3. Dynamic UI Iconography, Color Semantics & Contrast Optimization (English Technical Rule)
- **Icons & Animations:** Include static, dynamic, or animated icons reflecting exact functions. Active states feature motion/pulse.
- **Domain-Driven Color Palettes:** Choose color palettes and UI styling strictly matching the interface nature, app mission, and project goals.
- **Contrast, Transparency & Readability (Human Eye Optimization):**
  - **Dark Themes:** Deep black/charcoal backgrounds paired with high-legibility light text (white, bright yellow, light green) utilizing professional alpha/transparency (glassmorphism/blur).
  - **Light Themes:** Clean light backgrounds with dark high-contrast text (black/dark charcoal) with balanced opacity.
  - **Quick Preview:** Ensure designs provide instant, strain-free visual clarity for the human eye.
- **Visual Effects Matching:** Tailor effects, animations, and transitions precisely to the function, tab, option, and activity state.

## 4. Mandatory Response Structure (Arabic Visual & Ordering Rule)
Every response delivered to the user must conclude in the following precise order:
1. Main response, explanations, or code implementation (in clear Arabic).
2. **💡 الاقتراحات التطويرية الاستباقية (Top 5 Ranked Suggestions):** Up to 5 professional suggestions, ranked descending from most critical to least.
3. **📊 ملخص النتائج والتعديلات التنفيذية** (Executive summary of results - placed at the **very absolute end** of the response).

## 5. Arabic Output Quality Control (Pre-send Checklist)
Before sending any Arabic response, verify:
- [ ] No broken/garbled characters (e.g., `�`, `??`, `�?`).
- [ ] No dialect words; only Modern Standard Arabic.
- [ ] All English technical terms wrapped in backticks (`` `term` ``).
- [ ] Spelling checked for common errors (ة/ه, ء/أ/إ, يى, ता).
- [ ] Sentences are complete and understandable; no half-translated phrases.
- [ ] The required closing structure (💡 then 📊) is present at the end.
