
# TODO


# GENERAL

**- Rebrand and publish as Asareon Raam 1.0.0 **

**- Clean up deprecated code and unneeded migration/resolve notes (atleast agent feature)**

- Actions cleanup for token efficiency: Many of the actions can be consolidated into one action: f.ex. TOGGLE_MESSAGE_LOCKED / LOCK_MESSAGE / UNLOCK_MESSAGE to save tokens

- self contained "USB Stick" distributions in addition to installers

- Need a pre loaded state view that shows live log stream while app is loading


# ONBOARDING

- First run wizard
- Include default agents and a default session
- Now with Squirrel included!


# DOCUMENTATION

- Overall schematics of:
	- Core concepts
	- Feature dependency hierarchy. eg agent <- session <- filesystem. Who knows of and needs other features?
- Per Feature:
	- Scope and purpose
	- UI layouts

#Overall

- clean silent returns from the codebase to log an error (f.ex. "return@mapNotNull")
- run intellij code analysis and fix problems found


# FILESYSTEM
- filesystem.SEARCH_IN_FILE: Find a string in a file. Return the line number and whole line contents of all occurences of the string.
- filesystem.SEARCH_IN_MULTIPLE_FILES: Find a string in multiple file. Returns the filename, line number and whole line contents of all occurences of the string.
- filesystem.REPLACE_IN_FILE: Replaces a string in a file. Return the line number and whole line contents of all replaced occurences of the string. By default backups the old file to "filename.ext-isotimestamp.old".
- filesystem.REPLACE_IN_MULTIPLE_FILES: Replaces a string in multiple file. Returns the filename, line number and whole line contents of all replaced occurences of the string. By default backups the old files to "filename.ext-isotimestamp.old".
- filesystem.COPY: copy files or folders and allows giving new namews to them

# CORE
- identity visibilities to control who can see what parts of the identityscape? identity groups and permissions? If you belong to a group you see group assests (identities)? Needs planning.

- settings.ADD: add a new parameter "private" that blocks the public displaying of this variable everywhere (ui shows asterisks and  content is droppped from action payload), only targeted delivery of this setting allowed to the original owner via settings.PRIVATE_VALUE_CHANGED.

- Core Feature test coverage need analysis

- show big howering  < and > arrow buttons when more content available in the permissions manager

- Harden Identity: Move to its own feature, connect to SSI providers: host own SSI, IOTA Identity, other identity protocols/frameworks?

- ASK permissions granted log view in the identity/permissions tab.

- Allow selecting a custom root for App files (move to C:/Daniel for automatic backups!). Makes also possible to run multiple instances of the app.

- Add detection of multiple running app instances and warn/lock if trying to use same app folder

- BUG: Agent identities are not removed from core when agent is deleted
- BUG: stale identities remain at the core from session, agent, others?

- Runtime payload schema validation

- agent should be informed about the permissions it has alongside the action manifest

- scheduleDelayed re-enters via deferredDispatch. The implementation is platformDependencies.scheduleDelayed(delayMs) { deferredDispatch(originator, action) }. This means the delayed callback fires on whatever thread the platform schedules it on, then calls deferredDispatch, which adds to the queue and calls ensureProcessingLoop(). If the platform callback fires on a background thread while the main loop is already processing, ensureProcessingLoop will return immediately (because isDispatching is true) and the action sits in the queue until the current loop picks it up. But if the main loop has already finished, the callback starts a new processing loop on the background thread. This could be a thread-safety issue — deferredActionQueue is a plain mutableListOf, not thread-safe, and isDispatching has no synchronization. On JVM desktop this might be fine if everything runs on the main/UI thread, but on Android or with coroutine dispatchers it could be a subtle race.

- add ui header composable to share with features (ui consistency)

- App size is not set up correctly from settings upon app launch

- Global spelling checker. lib or API service? API service adds security vulnerability. Could use a small integrated agent for this in addition to library.

# SETTINGS
- API keys need to be hidden in settings. Adda sensitive flag to the setting.ADD action?


# AGENT
- thinking reporting: update agent current status. Upgrade status as two part status:current_task. Dependend on
- remove the boot sentinel from Sovereign and replace with Constitution sub-agent: instead of fragile one time check we get constant checks.
- fix resource files being saved always as json files. SHould be respective xml or md files per resource type definition.

BUG: gocnitivepipeline 969: should error not default

## Automatic mode
- Only trigger a turn when CommandBot has an error or a deliverable. OK messages should be ignored. maybe a setting "Ignore Ok from CommandBot"?

## Strategies 

### Minimal
- Minimal does not need CONTEXT_BUDGET

### Vanilla

### Private Thinking

### Sovereign
- Get Keel and Sage working, need HKG edit tools to fix errors
- BOOTING/AWAKE in SovereignStrategy is actually Strategy controlled variable so it should not live in NVRAM where the agent could modify it?

## NVRAM
- NVRAM schema adherence: Strategy sets or agent can expand?
- should the NVRAM needs be tied 1:1 to sub-agents? "My concerns are not being heard, I need to escalate my need." This is essentially an sub-agent veto.

## Avatar Card
- BUG: Triple and double agent avatar cards in the session and private session for new agents in sovereign mode

##Context management
- "You are <Agentname>" at the end of the context also for persona anchoring.
- NVRAM needs to be at the end of the context for optimal llm attention.

** - Session files shown in the agent context as session child-partials. Needs to go trough session to retrieve files: session files are sandboxed out of agents reach.**

- CONTEXT_NAVIGATION_REFERENCE: gather all context navigation information to one partial

# GATEWAY
- streaming support for gateways
- add MINIMAX API


# SESSION
- Need to be able to edit session names and reorder from the tab bar
- Delete session from tab right click menu
- TOGGLE_MESSAGE_COLLAPSED could allow none as message to toggle all messages states at once
- non destructive ledger deletions and edits. Why are we permanantly deleting entries when modern storage capacity is practically unlimited for text.
- Render Action items in JSON formatting with the CodeEditor
- Add emoticon and unicode search to the input

## WorkspacePane
- session files pane does not support folders and files in folders
- clicking an editable file should open the ui codeEditor

- How to handle input for and output files from agents without overriding the originals? session workspace: division to two folders with two permission sets?

## Ideas
- Remote Sessions: connect via freenet, tor, LoRa etc. Bypassing all bloated last gen cloud and web infrastructure.


# COMMANDBOT
- Implement the ASK process for ASK level permissions


# KNOWLEDGE GRAPH
- KG Editor needs the export KG button

- KG editor needs the graph vizualisation

- KG Editor needs approx token sizes for holons and also total approx for parent holons


# BACKUP
- Add a "Backups" manager to the burger global menu.
- Allow selecting backup folder location


## Ideas
- Multi-user HKGs: reservation system, git, other method?


# FUTURE IDEAS FOR FEATURES


# WEB ACCESS
- web search 
- wikipedia API andpoint

# GIT
- GIT Feature: connect to local and remote repos, permissions for pull (read), push (commit) and merge (manage)
