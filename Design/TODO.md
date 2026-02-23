

- Clean up deprecated code and unneeded migration/resolve notes (atleast agent feature)

- filesystem.SEARCH_IN_FILE: Find a string in a file. Return the line number and whole line contents of all occurences of the string.
- filesystem.SEARCH_IN_MULTIPLE_FILES: Find a string in multiple file. Returns the filename, line number and whole line contents of all occurences of the string.
- filesystem.REPLACE_IN_FILE: Replaces a string in a file. Return the line number and whole line contents of all replaced occurences of the string. By default backups the old file to "filename.ext-isotimestamp.old".
- filesystem.REPLACE_IN_MULTIPLE_FILES: Replaces a string in multiple file. Returns the filename, line number and whole line contents of all replaced occurences of the string. By default backups the old files to "filename.ext-isotimestamp.old".

- FileSystem is writing to core_daniel instead of the right directory "core". Lets evaluate this behaviour. Interfeature access and permissions are the responsibility of the feature? Or do we want to enforce filesystem access by the identity always even inside the features?

- settings.ADD: add a new parameter "private" that blocks the public displaying of this variable everywhere (ui *** and droppped from action payload), only targeted delivery of this setting allowed to the original owner via settings.PRIVATE_VALUE_CHANGED.

- Implement API requests limits according to provider specs

- Add session workspace file handling

- add agent controlled folding/unfolding of context partials.

- Core Feature test coverage need analysis


## HIGH PRIORITY:

- Check that the strategy contexes are constructed correctly for all strategies. Multiple session subsciptions are not currently put correctly to the context.

