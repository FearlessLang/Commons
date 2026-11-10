package fileAssociations;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import tools.Fs;
import utils.Bug;

/** Reconciles which program a set of file extensions opens on Windows, Linux or macOS, for a
 * whole family of otherwise-independent executables sharing one identity.
 *
 * Inputs to reconcile():
 * - identity: a string, supplied by the caller.
 * - belongsToFamily: a predicate over strings, supplied by the caller.
 * - command: an absolute executable path.
 * - extensions: a list of (file extension, file icon) pairs. May be empty.
 * - windows: a list of (window class, name, target, icon) entries. May be empty.
 * [Linux only] Windows and macOS ignore it.
 * - programIco, programPng: one icon, independent of extensions.
 * - replaced: applied in step 6 to the command of every identity found in step 1 whose
 * command is not command, before that identity is deleted.
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
 * (Linux) belongs to the identity equal to its own base name, without a final "-"
 * followed by hex digits. A helper bundle (macOS), a folder (identity).app in the
 * folder fearlessAssociations of ~/Library/Application Support, belongs to the identity
 * equal to its own base name.
 *
 * [macOS only] A program opens types, not extensions, and only the Info.plist of a
 * bundle declares a type. The bundle of command declares .fearless when it is built:
 * the helper bundle declares no type for .fearless. The declared type of an extension is
 * the first type Launch Services lists for the extension that a bundle outside
 * fearlessAssociations declares. The helper bundle of an identity holds only regular
 * files: Contents/Info.plist, Contents/MacOS/(identity), a script running the command
 * of the identity with the one argument FearlessCommandOpened, and in Contents/Resources one icns
 * file for each extension and one for the program.
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
 * line names it.
 *
 * Checks performed before any state is changed:
 *
 * 1. Every existing on-system identity for which belongsToFamily holds is collected -
 * Windows: every RegisteredApplications value name, and every name of a
 * Software\*\Capabilities key; Linux: the identity of every .desktop file and of every
 * MIME-package file; macOS: the identity of every helper bundle. If more than one distinct such identity is found, the
 * operation refuses, naming all of them, and changes nothing.
 *
 * 2. Windows: the ProgId of every
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\(extension)\UserChoice
 * and UserChoiceLatest is read, for every extension, with one reg query. The extension of a
 * UserChoice is the name of its FileExts key in lower case: the registry ignores the case
 * of a key name. A ProgId named there
 * counts when its extension is in extensions - any ProgId at all, including one belonging
 * to identity - or when its owner satisfies belongsToFamily. If any ProgId counts, the
 * operation refuses with userLocked applied to a map from every such extension, in the
 * order reg lists them, to the ProgIds that count.
 * Linux: a line of a choice file, in any section, that is not a comment (its first
 * character after any leading white space is not "#") mentions the family when its key is
 * application/x-fearless or application/x-fearless- followed by any text, or when a
 * .desktop file it names belongs to an identity for which belongsToFamily holds. If any
 * choice file holds such a line, also when extensions is empty, the operation refuses
 * with userLocked applied to a map from every such choice file, in the order the desktop
 * consults them, to those lines.
 * macOS: no check.
 * Nothing is changed.
 *
 * 3. For every Fearless extension in extensions, its current claimants are determined -
 * Windows: the ProgId named by Classes\(extension) (default value), plus every ProgId
 * named under Classes\(extension)\OpenWithProgids; Linux: the openers of its type, and
 * every system type of the extension; macOS: the bundle declaring the declared type
 * of the extension. For .fearless there are no system types.
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
 * macOS: no additional check;
 * Linux: every such .desktop file, MIME-package file, and (when the whole file would be
 * deleted) its containing directory, and the icon folders mimetypes and apps. If any are not writable, the operation refuses,
 * naming all of them.
 *
 * What happens once all checks pass:
 *
 * 5. If exactly one identity was found in step 1, it equals identity, and it already
 * declares exactly extensions (same extensions, same per-extension icons), the same
 * command, and the same program icon, steps 6 and 7 are skipped: this is a successful
 * call that changes no registry key and no file. [Linux only] It also requires the same system types for
 * every extension, the same windows, and that no icon file of identity is left beyond
 * those of extensions and windows.
 *
 * 6. Otherwise, replaced is applied to the command of every identity found in step 1
 * whose command is not command, then every identity found in step 1 is deleted in full - every registry key
 * or file it created, for every extension it declared, including extensions absent
 * from the current extensions list. [Windows only] For each such extension, a
 * now-empty OpenWithProgids key, and a now-empty Classes\(extension) key left with no
 * default value and no remaining subkeys, are removed too, rather than left behind as
 * an empty container. A default value of Classes\(extension) naming the identity goes back
 * to the value step 7 found there when the identity took the extension, kept in
 * Software\(identity)\PreviousDefaults; with none kept, the default value is deleted.
 * [Windows only] The "Open with" history Explorer keeps for each such extension, and for
 * each extension of step 7, loses the entries naming a program of the Fearless family
 * (FileExts\(extension)\OpenWithList and OpenWithProgids): Explorer would otherwise offer
 * a deleted member of the Fearless family.
 *
 * 7. If extensions is not empty, identity is created declaring exactly the extensions in
 * extensions, the same way for both kinds. For each: its own file icon; command
 * followed by the operating system's own file-argument placeholder ("%1" on Windows,
 * %f on Linux) as the open command. [Windows only] The default value of Classes\(extension)
 * found before the identity takes the extension is kept in Software\(identity)\PreviousDefaults.
 * programIco/programPng is recorded as identity's own
 * application icon, independent of the per-extension file icons. If extensions is
 * empty, nothing is created: identity is left entirely unregistered.
 * [Linux only] The MIME-package file defines the type of every extension, with the
 * glob "*" followed by the extension, weight 100, and one sub-class-of for each of its
 * system types; no other type is changed. The .desktop file lists those types. Each
 * icon is the file $XDG_DATA_HOME/icons/hicolor/256x256/mimetypes/(identity)-(hex
 * hash).png, and the program icon is apps/(identity).png there. Every file named
 * (identity)-(hex hash).png in mimetypes belongs to identity: deleting identity in
 * full, as step 6 and eradicateAll do, and recreating it delete those not wanted.
 * [Linux only] Each window is a .desktop file (identity)-(hex hash of the window
 * class).desktop with NoDisplay=true: the name of the window as its name, command
 * followed by the target of the window as its command, the absolute path of the icon
 * file of the window in mimetypes as its icon, and the window class as its
 * StartupWMClass. GNOME and COSMIC show that icon for every window whose WM_CLASS is
 * the window class; they do not find an icon named by its theme name in
 * $XDG_DATA_HOME/icons.
 * [macOS only] An extension with no declared type gets the type (identity with every
 * "_" replaced by "-")(extension): the helper bundle exports that type and opens that
 * type with the rank Owner. For an extension with a declared type, the helper bundle
 * opens the declared type with the rank Alternate, so identity is one of the "Open
 * With" programs of the extension. When a bundle outside /System declares that type
 * and the helper bundle of identity did not open that type before this call, the
 * desktop asks the user whether identity becomes the program opening that type.
 *
 * 8. One shell or database refresh is issued for the whole of steps 6-7 combined -
 * SHChangeNotify once (Windows) or update-mime-database/update-desktop-database once
 * (Linux) or LSRegisterURL on the helper bundle of identity once (macOS) - never per extension, never per intermediate write, and also when step 5
 * applied, so a changed image at the same path shows too; none when step 5 applied
 * with extensions empty. [macOS only] Each other identity deleted in step 6 is
 * registered once with no type before its files are deleted.
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
 * forgetChoices(choices, notWritable) removes the choices of a map given to userLocked,
 * and nothing else - Windows: the keys UserChoice and UserChoiceLatest of every extension
 * of choices, then one SHChangeNotify; Linux: every line of choices from its choice file,
 * the other lines of that file kept in their order. [Linux only] A choice file that is a
 * symbolic link is written through the link. [Linux only] If any choice file is not
 * writable, it refuses with notWritable, naming all of them, and changes nothing.
 * [Windows only] A reg delete that fails ends it with notWritable applied to the
 * command and its output.
 *
 * identities(belongsToFamily) is the list of step 1: every existing on-system identity
 * for which belongsToFamily holds, sorted. It changes nothing.
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
      List<Icon> extensions, List<WindowIcon> windows, Path programIco, Path programPng, Consumer<Path> replaced,
      Function<String,RuntimeException> ambiguous,
      Function<Map<String,List<String>>,RuntimeException> userLocked,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    if (Fs.isWindows()){
      WindowsAssociations.reconcile(identity, belongsToFamily, command, extensions, programIco, replaced,
        ambiguous, userLocked, notOurs, halfDone);
      return;
    }
    if (Fs.isLinux()){
      LinuxAssociations.reconcile(identity, belongsToFamily, command, extensions, windows, programPng, replaced,
        ambiguous, userLocked, notOurs, notWritable, halfDone);
      return;
    }
    if (Fs.isMac()){
      MacAssociations.reconcile(identity, belongsToFamily, command, extensions, programPng, replaced,
        ambiguous, notOurs, halfDone);
      return;
    }
    throw Bug.unreachable();
  }
  static List<String> identities(Predicate<String> belongsToFamily){
    if (Fs.isWindows()){ return WindowsAssociations.existingIdentities(belongsToFamily); }
    if (Fs.isLinux()){ return LinuxAssociations.existingIdentities(belongsToFamily); }
    if (Fs.isMac()){ return MacAssociations.existingIdentities(belongsToFamily); }
    throw Bug.unreachable();
  }
  static void forgetChoices(Map<String,List<String>> choices, Function<String,RuntimeException> notWritable){
    if (Fs.isWindows()){ WindowsAssociations.forgetChoices(choices, notWritable); return; }
    if (Fs.isLinux()){ LinuxAssociations.forgetChoices(choices, notWritable); return; }
    throw Bug.unreachable();
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    if (Fs.isWindows()){ WindowsAssociations.eradicateAll(belongsToFamily); return; }
    if (Fs.isLinux()){ LinuxAssociations.eradicateAll(belongsToFamily, halfDone); return; }
    if (Fs.isMac()){ MacAssociations.eradicateAll(belongsToFamily, halfDone); return; }
    throw Bug.unreachable();
  }
}
