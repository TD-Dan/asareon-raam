
# TODO


# GENERAL

**- Rebrand and publish as 1.0.0alpha! < Asareon Raam > < Asareon AUF (Agents Universal Framework)> **

**- Clean up deprecated code and unneeded migration/resolve notes (atleast agent feature)**

- Actions cleanup for token efficiency: Many of the actions can be consolidated into one action: f.ex. TOGGLE_MESSAGE_LOCKED / LOCK_MESSAGE / UNLOCK_MESSAGE to save tokens

# DOCUMENTATION

- Overall schematics of:
	- Core concepts
	- Feature dependency hierarchy. eg agent <- session <- filesystem. Who knows of and needs other features?
- Per Feature:
	- Scope and purpose
	- UI layouts

# FILESYSTEM
- filesystem.SEARCH_IN_FILE: Find a string in a file. Return the line number and whole line contents of all occurences of the string.
- filesystem.SEARCH_IN_MULTIPLE_FILES: Find a string in multiple file. Returns the filename, line number and whole line contents of all occurences of the string.
- filesystem.REPLACE_IN_FILE: Replaces a string in a file. Return the line number and whole line contents of all replaced occurences of the string. By default backups the old file to "filename.ext-isotimestamp.old".
- filesystem.REPLACE_IN_MULTIPLE_FILES: Replaces a string in multiple file. Returns the filename, line number and whole line contents of all replaced occurences of the string. By default backups the old files to "filename.ext-isotimestamp.old".

- ECHO_FILE_TO_SESSION

- FileSystem is writing to core_daniel instead of the right directory "core". Lets evaluate this behaviour. Interfeature access and permissions are the responsibility of the feature? Or do we want to enforce filesystem access by the identity always even inside the features?


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

- user files should go to core/<username> instead of core root.

# SETTINGS
- API keys need to be hidden in settings. Adda sensitive flag to the setting.ADD action?


# AGENT
- thinking reporting: update agent current status. Upgrade status as two part status:current_task.
- remove the boot sentinel from Sovereign and replace with Constitution sub-agent: instead of fragile one time check we get constant checks.


BUG: gocnitivepipeline 969: should error not default

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


# SESSION
- Need to be able to edit session names and reorder from the tab bar
- "add agent" isnt visible in the session kebab menu, until agent is removed from the session. Please write a test case "add agent is visible and populated with agents"
- TOGGLE_MESSAGE_COLLAPSED could allow none as message to toggle all messages states at once

BUG: "add agent" isn't visible in the session kebab menu, until agent is removed from the session. Please write a test case "add agent is visible and populated with agents". Probaply its visibility is gated by the AGENTS_UPDATED action broadcast and it stays hidden till it receives it?

## WorkspacePane
- session files pane does not support folders and files in folders
- clicking an editable file should open the ui codeEditor

- How to handle input for and output files from agents without overriding the originals? session workspace: division to two folders with two permission sets?

## Ideas
- Remote Sessions: connect via freenet, tor, LoRa etc. Bypassing all bloated last gen cloud and web infrastructure.


# COMMANDBOT
- CommandBot by default posts collapsed messages when OK

# KNOWLEDGE GRAPH
- KG Editor needs the export KG button

- KG editor needs the graph vizualisation

- KG Editor needs approx token sizes for holons and also total approx for parent holons

## Ideas
- Multi-user HKGs: reservation system, git, other method?


# FUTURE IDEAS FOR FEATURES

# BACKUP
- AUTOMATIC SNAPSHOT BACKUP at app start! And a backup central.

# WEB ACCESS
- web search 
- wikipedia API andpoint

# GIT
- GIT Feature: connect to local and remote repos, permissions for pull (read), push (commit) and merge (manage)
