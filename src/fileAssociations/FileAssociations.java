package fileAssociations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import tools.Fs;
import utils.Bug;
import utils.Join;

/** Reconciles which program a set of file extensions opens on Windows, Linux or macOS, for a
 * whole family of otherwise-independent executables sharing one identity.
 *
 * Inputs to reconcile():
 * - identity: a string, supplied by the caller.
 * - name: the application name the desktop shows for identity.
 * - belongsToFamily: a predicate over strings, supplied by the caller.
 * - command: an absolute executable path.
 * - extensions: a list of (file extension, file icon) pairs. May be empty.
 * - taken: the extensions of extensions that identity may take from a program for which
 * belongsToFamily does not hold. May be empty.
 * - windows: a list of (window class, name, target, icon) entries. May be empty.
 * [Linux only] Windows and macOS ignore it.
 * - programPng: one icon, independent of extensions.
 * [Linux and macOS only] Windows uses the icon inside command.
 * - replaced: applied in step 5 to the command of every identity found in step 1 whose
 * command is not command, before that identity is deleted.
 * - ambiguous, notOurs, notWritable, halfDone: one function per kind of failure, each
 * turning what was found into the exception reconcile throws.
 *
 * Each extension is of one of two kinds, given by its text (Icon.system): .fearless,
 * .fapp followed by three digits and .ffile followed by three digits are Fearless
 * extensions, every other extension is a system extension. Both kinds get the same setup
 * and the same checks.
 *
 * A ProgId (Windows) belongs to identity X if it equals X followed by "." followed by
 * the extension with its leading dot removed. A .desktop file or MIME-package file
 * (Linux) belongs to the identity equal to its own base name, without a final "-"
 * followed by hex digits. A helper bundle (macOS), a folder (identity).app in the
 * folder fearlessAssociations of ~/Library/Application Support, belongs to the identity
 * equal to its own base name.
 *
 * The holder of an extension is the program the desktop opens a file named "x" followed
 * by the extension with, by its name -
 * Windows: the ProgId of the key UserChoiceLatest of
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\(extension), else of
 * the key UserChoice there, else the default value of HKEY_CLASSES_ROOT\(extension); its
 * name is the identity the ProgId belongs to, else the whole ProgId. Linux: the default program of the first type that has one in the list
 * starting with the type of the glob of highest weight matching the name in the MIME
 * database, where each type of the list adds to the end of the list each of its parent
 * types (the second field of each line of a subclasses file of the MIME database whose
 * first field is that type) not yet in the list. The default program of a type is
 * the first of: the first installed .desktop file
 * of the key of that type in the section [Default Applications] of the choice files, in
 * the order the desktop consults them; the same in the section [Added Associations]; the
 * first .desktop file whose MimeType= line names that type, among the .desktop files with no
 * .desktop file of the same name in an earlier applications folder; the last two skip every
 * .desktop file of the key of that type in the section [Removed Associations] of a choice
 * file. Its name is the base name of
 * that .desktop file. No glob matching the name, or no such .desktop file, is no holder.
 * macOS: the application Launch Services gives as the default for the preferred type of
 * the extension; its name is the file name of that application without ".app".
 * The holder is in the family when belongsToFamily holds for its name. The holder is
 * identity itself when its name is identity.
 *
 * [macOS only] A program opens types, not extensions, and only the Info.plist of a
 * bundle declares a type. The bundle of command declares no type: the helper bundle
 * declares .fearless like every other extension. The declared type of an extension is
 * the first type Launch Services lists for the extension that a bundle outside
 * fearlessAssociations declares. The helper bundle of an identity holds only regular
 * files: Contents/Info.plist, Contents/MacOS/(identity), a script running the command
 * of the identity with the one argument FearlessCommandOpened, and in Contents/Resources one icns
 * file for each extension and one for the program, and en.lproj/InfoPlist.strings, giving name as the
 * CFBundleDisplayName (macOS shows that name only when the Info.plist names the bundle by its file name).
 *
 * [Linux only] A program opens MIME types, not extensions. The type of .fearless is
 * application/x-fearless; the type of every other extension is application/x-fearless-
 * followed by the extension without its dot. The MIME database is the mime folder of
 * $XDG_DATA_HOME and of every $XDG_DATA_DIRS entry: the globs of globs2, matched as
 * file name patterns (ignoring case unless the glob has the cs flag; a type with
 * __NOGLOBS__ in a folder ignores its globs in every later folder); the folder of
 * $XDG_DATA_HOME, rebuilt after every change, leaves out the types no MIME-package file
 * there names. The system types of an extension are the types matching "*" followed by
 * the extension in that database, where each folder also leaves out the types with a glob
 * in its own MIME-package files of identities for which belongsToFamily holds. A choice
 * file is a mimeapps.list or (desktop)-mimeapps.list, (desktop) each name of
 * $XDG_CURRENT_DESKTOP in lower case, in $XDG_CONFIG_HOME, in every $XDG_CONFIG_DIRS entry
 * and in every applications folder.
 *
 * Checks performed before any state is changed:
 *
 * 1. Every existing on-system identity for which belongsToFamily holds is collected -
 * Windows: every RegisteredApplications value name, and every name of a
 * Software\*\Capabilities key; Linux: the identity of every .desktop file and of every
 * MIME-package file; macOS: the identity of every helper bundle. If more than one distinct such identity is found, the
 * operation refuses, naming all of them, and changes nothing.
 *
 * 2. The holder of every extension of extensions is determined. If the holder of an
 * extension not in taken is not in the family, the operation refuses with notOurs applied
 * to a map from each such extension, in the order of extensions, to the name of its
 * holder, and changes nothing. The taken over extensions are the extensions of taken, and
 * the extensions whose holder is in the family and is not identity itself.
 *
 * 3. Every location identity would need to write to, and every location an
 * identity marked for removal would need to be removed from, is confirmed writable - Windows: the
 * user holds the right DELETE on the keys UserChoice and UserChoiceLatest of every taken over
 * extension, and on each of their subkeys; the other registry keys under HKCU are always
 * removable by their owner; Linux: every
 * such .desktop file, MIME-package file, and (when the whole file would be deleted) its
 * containing directory, the icon folders mimetypes and apps, and every choice file of
 * step 4; macOS: every folder of the helper bundle of an identity found in step 1, the
 * folder fearlessAssociations, and, when an extension is taken over, the file of the
 * LaunchServices preferences (Library/Preferences/(preferences).plist in the home folder):
 * macOS reports no failure when it can not write that file. If any are not writable, the
 * operation refuses with notWritable, naming all of them.
 *
 * What happens once all checks pass:
 *
 * 4. Every taken over extension is given to identity, also when step 5 is skipped -
 * Windows: the keys UserChoice and UserChoiceLatest of the extension are deleted; a
 * deletion that fails ends the operation with notWritable applied to the name of the
 * Windows function, the key and the text Windows gives for the error code. Linux: every line of a choice file, in any section, whose key is the type of
 * the extension is removed, the other lines of that file kept in their order; a choice
 * file that is a symbolic link is written through the link. macOS: in the array LSHandlers
 * of the LaunchServices preferences (the domain preferences), a choice making the helper
 * bundle of identity (its bundle identifier: identity with "-" for every "_") the program
 * of every role of the type of the extension (its declared type, or the type of step 6)
 * replaces every choice for that type; preferences that report a failed write end the
 * operation with notWritable applied to the domain. Then the command reload restarts lsd,
 * once, before any registration of steps 5-7: lsd reads those preferences only when it
 * starts, and a restart loses the registrations of the seconds before it. A reload that
 * fails ends the operation with notWritable applied to the command and its output.
 *
 * 5. If exactly one identity was found in step 1, it equals identity, and it already
 * declares exactly extensions (same extensions, same per-extension icons), the same
 * command, the same name, and the same program icon, step 6 is skipped: no registry key and no file
 * of a setup changes. [Linux only] It also requires the same system types for
 * every extension, the same windows, and that no icon file of identity is left beyond
 * those of extensions and windows. [macOS only] It also requires the same declared type
 * for every extension. [Windows only] It also requires that the default value
 * of Classes\(extension) names the ProgId of identity for every extension, and that no ProgId
 * of the family is left that no identity of step 1 declares.
 * Otherwise replaced is applied to the command of every identity found in step 1
 * whose command is not command, then every identity found in step 1 is deleted in full - every registry key
 * or file it created, for every extension it declared, including extensions absent
 * from the current extensions list. [Windows only] For each such extension, a
 * now-empty OpenWithProgids key, and a now-empty Classes\(extension) key left with no
 * default value and no remaining subkeys, are removed too, rather than left behind as
 * an empty container. A default value of Classes\(extension) naming the identity goes back
 * to the value step 6 found there when the identity took the extension, kept in
 * Software\(identity)\PreviousDefaults; with none kept, the default value is deleted.
 * [Windows only] Every ProgId with a key in Classes that belongs to an identity for which
 * belongsToFamily holds, and that no identity of step 1 declares, is deleted the same way, for
 * the extension the ProgId ends with.
 * [Windows only] The "Open with" history Explorer keeps for each such extension, and for
 * each extension of step 6, loses the entries naming a program of the Fearless family
 * (FileExts\(extension)\OpenWithList and OpenWithProgids): Explorer would otherwise offer
 * a deleted member of the Fearless family.
 *
 * 6. If extensions is not empty, identity is created declaring exactly the extensions in
 * extensions, the same way for both kinds. For each: its own file icon; command
 * followed by the operating system's own file-argument placeholder ("%1" on Windows,
 * %f on Linux) as the open command. [Windows only] The default value of Classes\(extension)
 * found before the identity takes the extension is kept in Software\(identity)\PreviousDefaults.
 * command (Windows) or programPng (Linux and macOS) is recorded as identity's own
 * application icon, and name as identity's own application name, independent of the per-extension file icons. If extensions is
 * empty, nothing is created: identity is left entirely unregistered.
 * [Linux only] The MIME-package file defines the type of every extension, with the
 * glob "*" followed by the extension, weight 100, one sub-class-of for each of its
 * system types, and its icon; no other type is changed. The .desktop file lists those types. Each
 * icon is the file $XDG_DATA_HOME/icons/hicolor/256x256/mimetypes/(identity)-(hex
 * hash).png, and the program icon is apps/(identity).png there. Every file named
 * (identity)-(hex hash).png in mimetypes belongs to identity: deleting identity in
 * full, as step 5 and eradicateAll do, and recreating it delete those not wanted.
 * [Linux only] Each window is a .desktop file (identity)-(hex hash of the window
 * class).desktop with NoDisplay=true: the name of the window as its name, command
 * followed by the target of the window as its command, the absolute path of the icon
 * file of the window in mimetypes as its icon, and the window class as its
 * StartupWMClass. GNOME and COSMIC show that icon for every window whose WM_CLASS is
 * the window class; they do not find an icon named by its theme name in
 * $XDG_DATA_HOME/icons.
 * [macOS only] An extension with no declared type gets the type (identity followed by the
 * extension, with "-" for every "_" and in lower case): the helper bundle exports that
 * type and opens that type with the rank Owner. For an extension with a declared type, the helper bundle
 * opens the declared type with the rank Alternate, so identity is one of the "Open
 * With" programs of the extension.
 *
 * 7. One shell or database refresh is issued for the whole of steps 4-6 combined -
 * SHChangeNotify once (Windows) or update-mime-database/update-desktop-database once
 * (Linux) or LSRegisterURL on the helper bundle of identity once (macOS) - never per extension, never per intermediate write, and also when step 5
 * skipped step 6, so a changed image at the same path shows too; none when step 5 skipped
 * step 6 with extensions empty. [macOS only] Each identity deleted in step 5 and not
 * created again in step 6 is registered once with no type before its files are deleted.
 *
 * What is guaranteed, and what is not:
 *
 * - If any check in steps 1-3 fails, no registry key or file has been touched.
 * - If step 4, 5 or 6 fails partway through because the underlying OS write itself fails,
 * the system may be left with neither the old identity nor the complete new one
 * present; the resulting error reports this rather than asserting a specific residual
 * state.
 * - A successful return means identity declares exactly, and only, the extensions in
 * extensions, and holds each of them - or, when extensions is empty, that identity is not
 * registered at all.
 *
 * identities(belongsToFamily) is the list of step 1: every existing on-system identity
 * for which belongsToFamily holds, sorted. It changes nothing.
 *
 * eradicateAll(belongsToFamily) performs none of the checks above: every existing
 * on-system identity for which belongsToFamily holds, however many there are, is
 * deleted in full, and nothing is created. [Windows only] Every ProgId of step 5 that no such
 * identity declares is deleted too. [Linux only] Every icon file in mimetypes
 * named (name)-(hex hash).png, and in apps named (name).png, for a name for which
 * belongsToFamily holds is deleted too. One shell or database refresh is issued for the
 * whole operation, or none at all if no identity matched and, on Windows, no such ProgId was
 * found.
 */
public final class FileAssociations{
  public static void reconcile(String identity, String name, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, List<String> taken, List<WindowIcon> windows, Path programPng, Consumer<Path> replaced,
      Function<String,RuntimeException> ambiguous,
      Function<Map<String,String>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var names= extensions.stream().map(Icon::extension).toList();
    assert names.containsAll(taken);
    var existing= identities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(Join.of(existing, "", "\n", "")); }
    var holders= Fs.isWindows() ? WindowsAssociations.holders(names) : Fs.isLinux() ? LinuxAssociations.holders(names) : MacAssociations.holders(names);
    var foreign= new LinkedHashMap<>(holders);
    foreign.keySet().removeAll(taken);
    foreign.values().removeIf(belongsToFamily);
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    var takenOver= names.stream().filter(e -> taken.contains(e) || !holders.getOrDefault(e, identity).equals(identity)).toList();
    if (Fs.isWindows()){ WindowsAssociations.reconcile(identity, name, belongsToFamily, command, extensions, existing, takenOver, replaced, notWritable, halfDone); return; }
    if (Fs.isLinux()){ LinuxAssociations.reconcile(identity, name, belongsToFamily, command, extensions, existing, takenOver, windows, programPng, replaced, notWritable, halfDone); return; }
    MacAssociations.reconcile(identity, name, command, extensions, existing, takenOver, programPng, replaced, notWritable, halfDone);
  }
  public static List<String> identities(Predicate<String> belongsToFamily){
    if (Fs.isWindows()){ return WindowsAssociations.existingIdentities(belongsToFamily); }
    if (Fs.isLinux()){ return LinuxAssociations.existingIdentities(belongsToFamily); }
    if (Fs.isMac()){ return MacAssociations.existingIdentities(belongsToFamily); }
    throw Bug.unreachable();
  }
  public static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    if (Fs.isWindows()){ WindowsAssociations.eradicateAll(belongsToFamily, halfDone); return; }
    if (Fs.isLinux()){ LinuxAssociations.eradicateAll(belongsToFamily, halfDone); return; }
    if (Fs.isMac()){ MacAssociations.eradicateAll(belongsToFamily, halfDone); return; }
    throw Bug.unreachable();
  }
  static boolean same(List<String> existing, String identity, List<Path> files, Map<Path,byte[]> wanted){
    var exactlyWanted= existing.stream().allMatch(identity::equals) && Set.copyOf(files).equals(wanted.keySet());
    if (!exactlyWanted){ return false; }
    return wanted.entrySet().stream().allMatch(e -> Arrays.equals(Fs.of(() -> Files.readAllBytes(e.getKey())), e.getValue()));
  }
  static String baseName(Path file){
    var name= file.getFileName().toString();
    return name.substring(0, name.lastIndexOf('.'));
  }
}
