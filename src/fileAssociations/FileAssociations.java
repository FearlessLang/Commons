package fileAssociations;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import tools.Fs;
import utils.Bug;

/** Reconciles which program a set of file extensions opens on Windows or Linux, for a
 * whole family of otherwise-independent executables sharing one identity.
 *
 * Inputs to reconcile():
 * - identity: a string, supplied by the caller.
 * - belongsToFamily: a predicate over strings, supplied by the caller.
 * - command: an absolute executable path.
 * - extensions: a list of (file extension, file icon) pairs. May be empty.
 * - programIco, programPng: one icon, independent of extensions.
 * - ambiguous, userLocked, notOurs, notWritable, halfDone: one function per kind of
 * failure, each turning what was found into the exception reconcile throws.
 *
 * Each extension is of one of two kinds, given by its text (Icon.system): .fearless,
 * .fapp followed by three digits and .ffile followed by three digits are Fearless
 * extensions, every other extension is a system extension. A Fearless extension
 * belongs to the family alone; a system extension is taken over from whatever program
 * opens it.
 *
 * A ProgId (Windows) belongs to identity X if it equals X followed by "." followed by
 * the extension with its leading dot removed. A .desktop file or MIME-package file
 * (Linux) belongs to the identity equal to its own base name.
 *
 * [Linux only] A program opens MIME types, not extensions. The type of .fearless is
 * application/x-fearless; the type of every other extension is application/x-fearless-
 * followed by the extension without its dot. The MIME database is the mime folder of
 * $XDG_DATA_HOME and of every $XDG_DATA_DIRS entry: the globs of globs2, matched as
 * file name patterns against "*" followed by the extension (ignoring case unless the
 * glob has the cs flag; a type with __NOGLOBS__ in a folder ignores its globs in every
 * later folder). Each folder leaves out the types with a glob in its own MIME-package
 * files of identities for which belongsToFamily holds; the folder of $XDG_DATA_HOME,
 * rebuilt after every change, also leaves out the types no other MIME-package file
 * there names. The system types of an extension other than .fearless are the types of
 * the globs matching it. The openers of a type are every .desktop file whose MimeType=
 * line names it, and every [Default Applications] and [Added Associations] entry of a
 * choice file naming it.
 *
 * Checks performed before any state is changed:
 *
 * 1. Every existing on-system identity for which belongsToFamily holds is collected -
 * Windows: every RegisteredApplications value name, and every name of a
 * Software\*\Capabilities key; Linux: every .desktop file base name, and every
 * MIME-package file base name. If more than one distinct such identity is found, the
 * operation refuses, naming all of them, and changes nothing.
 *
 * 2. Windows: for every extension in extensions,
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\(extension)\UserChoice
 * and UserChoiceLatest are read. If a value is present there for any of them - naming
 * any ProgId at all, including one belonging to identity - the operation refuses with
 * userLocked applied to the list of every such extension, in the order of extensions.
 * No program can remove or change this value: Settings, Apps, Default apps, Reset is
 * the only way to clear it, and Reset affects every app default on the machine.
 * Linux: for every system extension in extensions, a [Default Applications] entry of a
 * choice file naming its type and a program whose identity does not satisfy
 * belongsToFamily is the user's own choice: the operation refuses with userLocked
 * applied to the list of every such extension, in the order of extensions.
 * Nothing is changed.
 *
 * 3. For every Fearless extension in extensions, its current claimants are determined -
 * Windows: the ProgId named by Classes\(extension) (default value), plus every ProgId
 * named under Classes\(extension)\OpenWithProgids; Linux: the openers of its type, and
 * every system type of the extension. For .fearless the [Added Associations] entries
 * are not openers and there are no system types.
 * [Windows only] A registry value reg.exe itself reports as never having been set (the
 * literal text "(value not set)") is deleted on the spot and counted as no claimant at
 * all, rather than as a claimant named "(value not set)".
 * If any claimant is a program whose owning identity does not satisfy belongsToFamily,
 * or a system type, the operation refuses with notOurs applied to a map from each such
 * extension, in the order of extensions, to those claimants, and changes nothing.
 * System extensions have no claimants: the programs opening them do not refuse them.
 *
 * 4. Every location identity would need to write to, and every location an identity
 * marked for removal would need to be removed from, is confirmed writable - Windows: no
 * additional check, registry keys under HKCU are always removable by their owner;
 * Linux: every such .desktop file, MIME-package file, and (when the whole file would be
 * deleted) its containing directory, and the icon folders mimetypes and apps. If any are not writable, the operation refuses,
 * naming all of them.
 *
 * What happens once all checks pass:
 *
 * 5. If exactly one identity was found in step 1, it equals identity, and it already
 * declares exactly extensions (same extensions, same per-extension icons), the same
 * command, and the same program icon, nothing further happens: this is a successful
 * call that changes nothing. [Linux only] It also requires the same system types for
 * every extension, and that no icon file of identity is left beyond those of extensions.
 *
 * 6. Otherwise, every identity found in step 1 is deleted in full - every registry key
 * or file it created, for every extension it declared, including extensions absent
 * from the current extensions list. [Windows only] For each such extension, a
 * now-empty OpenWithProgids key, and a now-empty Classes\(extension) key left with no
 * default value and no remaining subkeys, are removed too, rather than left behind as
 * an empty container.
 *
 * 7. If extensions is not empty, identity is created declaring exactly the extensions in
 * extensions, the same way for both kinds. For each: its own file icon; command
 * followed by the operating system's own file-argument placeholder ("%1" on Windows,
 * %f on Linux) as the open command. programIco/programPng is recorded as identity's own
 * application icon, independent of the per-extension file icons. If extensions is
 * empty, nothing is created: identity is left entirely unregistered.
 * [Linux only] The MIME-package file defines the type of every extension, with the
 * glob "*" followed by the extension, weight 100, and one sub-class-of for each of its
 * system types; no other type is changed. The .desktop file lists those types. Each
 * icon is the file $XDG_DATA_HOME/icons/hicolor/256x256/mimetypes/(identity)-(hex
 * hash).png, and the program icon is apps/(identity).png there. Every file named
 * (identity)-(hex hash).png in mimetypes belongs to identity: deleting identity in
 * full, as step 6 and eradicateAll do, and recreating it delete those not wanted.
 *
 * 8. One shell or database refresh is issued for the whole of steps 6-7 combined -
 * SHChangeNotify once (Windows) or update-mime-database/update-desktop-database once
 * (Linux) - never per extension, never per intermediate write, and never at all when
 * step 5 applied.
 *
 * What is guaranteed, and what is not:
 *
 * - If any check in steps 1-4 fails, no registry key or file has been touched, except
 * for a never-set value deleted in step 3 as described above.
 * - If step 6 or 7 fails partway through because the underlying OS write itself fails,
 * the system may be left with neither the old identity nor the complete new one
 * present; the resulting error reports this rather than asserting a specific residual
 * state.
 * - A successful return means identity declares exactly, and only, the extensions in
 * extensions - or, when extensions is empty, that identity is not registered at all.
 *
 * eradicateAll(belongsToFamily) performs none of the checks above: every existing
 * on-system identity for which belongsToFamily holds, however many there are, is
 * deleted in full, and nothing is created. [Linux only] Every icon file in mimetypes
 * named (name)-(hex hash).png, and in apps named (name).png, for a name for which
 * belongsToFamily holds is deleted too. One shell or database refresh is issued for the
 * whole operation, or none at all if no identity matched.
 */
public interface FileAssociations{
  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programIco, Path programPng,
      Function<String,RuntimeException> ambiguous,
      Function<List<String>,RuntimeException> userLocked,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    if (Fs.isWindows()){
      WindowsAssociations.reconcile(identity, belongsToFamily, command, extensions, programIco,
        ambiguous, userLocked, notOurs, halfDone);
      return;
    }
    if (Fs.isLinux()){
      LinuxAssociations.reconcile(identity, belongsToFamily, command, extensions, programPng,
        ambiguous, userLocked, notOurs, notWritable, halfDone);
      return;
    }
    throw Bug.unreachable();
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    if (Fs.isWindows()){ WindowsAssociations.eradicateAll(belongsToFamily); return; }
    if (Fs.isLinux()){ LinuxAssociations.eradicateAll(belongsToFamily, halfDone); return; }
    throw Bug.unreachable();
  }
}
