## What and why

<!-- Link the issue: "Closes #NN" -->

## How it was tested

- Tests added/updated:
- Tried on a phone / narrow (375px) viewport (student pages):

## Checklist

- [ ] `./mvnw verify` passes locally (Testcontainers tests need Docker; CI runs them regardless)
- [ ] Every schema change has a new Flyway migration; no applied migration was edited
- [ ] No anti-cheating rule was weakened (CLAUDE.md), or the change was agreed in the linked issue
- [ ] New anti-cheat behaviour has an integration test
- [ ] No secrets, `.env` or `application-local.yml` committed
