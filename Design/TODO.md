
- Clean up deprecated code and unneeded migration/resolve notes (atleast agent feature)

- filesystem.SEARCH_IN_FILE: Find a string in a file. Return the line number and whole line contents of all occurences of the string.
- filesystem.SEARCH_IN_MULTIPLE_FILES: Find a string in multiple file. Returns the filename, line number and whole line contents of all occurences of the string.
- filesystem.REPLACE_IN_FILE: Replaces a string in a file. Return the line number and whole line contents of all replaced occurences of the string. By default backups the old file to "filename.ext-isotimestamp.old".
- filesystem.REPLACE_IN_MULTIPLE_FILES: Replaces a string in multiple file. Returns the filename, line number and whole line contents of all replaced occurences of the string. By default backups the old files to "filename.ext-isotimestamp.old".

- ECHO_FILE_TO_SESSION

- FileSystem is writing to core_daniel instead of the right directory "core". Lets evaluate this behaviour. Interfeature access and permissions are the responsibility of the feature? Or do we want to enforce filesystem access by the identity always even inside the features?

- settings.ADD: add a new parameter "private" that blocks the public displaying of this variable everywhere (ui *** and droppped from action payload), only targeted delivery of this setting allowed to the original owner via settings.PRIVATE_VALUE_CHANGED.

- Add session workspace file handling

- add agent controlled folding/unfolding of context partials.

- Core Feature test coverage need analysis

- Remote Sessions: connect via freenet, tor, LoRa etc. Bypassing all bloated last gen cloud and web infrastructure.

- Multi-user HKGs: reservation system, git, other method?

- Harden Identity: Move to its own feature, connect to SSI providers: host own SSI, IOTA Identity, other identity protocols/frameworks?

- ASK permissions granted log view in the identity/permissions tab.

- API keys need to be hidden in settings. Adda sensitive flag to the setting.ADD action?

- show only in context the Actions that the agent has permission for

- BUG: Agent identities are not removed from core when agent is deleted

- remove the boot sentinel from Sovereign and replace with Constitution sub-agent: instead of fragile one time check we get constant checks.

- should the NVRAM needs be tied 1:1 to sub-agents? "My concerns are not being heard, I need to escalate my need." This is essentially an sub-agent veto.

- GIT Feature: connect to local and remote repos, permissions for pull (read), push (commit) and merge (manage)

- user files should go to core/<username> instead of core root.

- Preview window shows on top a bar with every context partials percentage of the total context

- KG Editor needs the export KG button

- KG editor needs the graph vizualisation

- KG Editor needs approx token sizes for holons and also total approx for parent holons

- Allow selecting a custom root for App files (move to C:/Daniel for automatic backups)

- AVAILABLE_ACTIONS: collpasible by feature to [EXPANDED / ESSENTIAL / COLLAPSED]

- CONTEXT_NAVIGATION_REFERENCE: gather all context navigation information to one partial

- TOGGLE_MESSAGE_COLLAPSED could allow none as message to toggle all messages states at once

- Many of the actions can be consolidated into one action: f.ex. TOGGLE_MESSAGE_LOCKED / LOCK_MESSAGE / UNLOCK_MESSAGE to save tokens

- Dont show the AVAILABLE_ACTIONS partial if no actions are available for the agent.

- Show newest 3 files in the WORKSPACE_INDEX

- WORKSPACE_INDEX should default to uncollapsed?

- Session is saving the input draft on evey keystroke or with a really fast debounce. This should be longer say 3 seconds and fire only in 3 second intervals.

## HIGH PRIORITY:

- !! Need context collapse management tools to the preview turn view to debug and fix agent internal state

- AUTOMATIC INCREMENTAL BACKUP at app start! And a backup central.

- Get Sovereigns to work -> offloading design issue tracking and AUF App. Needs Boot and NVRAM functionality.

- Build a context collapse and expand functionality for the agents. Instead of only choosing what holons to show the agent can select its  whole context content dynamically.

- Rebrand and publish as 1.0.0alpha! < Asareon Raam > < Asareon AUF (Agents Universal Framework)> 

- clean up deprecated code

- Check that the all strategy contexes are constructed correctly for all strategies. Multiple session subsciptions are not currently put correctly to the context. PrivateSessionStrategy can work as a reference implementation for this.

