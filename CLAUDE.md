# Coding guidelines

Follow these on every change in this repository (adapted from
[multica-ai/andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills/blob/main/CLAUDE.md)).

## 1. Think before coding
- State your assumptions and the tradeoffs before starting non-trivial work.
- If something is unclear, ask instead of guessing. Prefer caution over speed.

## 2. Simplicity first
- Write the minimum code that solves the problem.
- No speculative features, single-use abstractions or configurability nobody asked for.

## 3. Surgical changes
- Touch only what the request needs.
- Don't refactor or reformat nearby code. Remove only what your own change made unused.
- Mention unrelated dead code or problems instead of deleting or fixing them unasked.

## 4. Goal-driven execution
- Turn each task into a goal you can check (for a bug: reproduce it first, ideally with a test, then fix it).
- For multi-step work, give a short plan with how each step will be verified, and verify before calling it done.

## 5. Legal first
- Consider legality in every change. The owner must not be exposed to lawsuits.
- Respect copyrights and trademarks: no copied characters, logos, brand names, art, code or data without a compatible licence; credit every third-party component on the licences page.
- Keep the privacy policy, terms and disclaimers accurate whenever a feature changes what data is collected or where it goes. Anything that sends user data elsewhere stays optional and asks first.
- Follow each outside service's terms (APIs, app stores, data sources). Don't scrape or bypass access controls.
- No medical, financial or legal advice presented as professional advice; keep the "not professional advice" disclaimers.
- When something is a legal grey area, stop and flag it instead of shipping it.
