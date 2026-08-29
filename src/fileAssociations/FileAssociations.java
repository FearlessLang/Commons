package fileAssociations;

import java.nio.file.Path;
import java.util.List;
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
 *
 * A ProgId (Windows) belongs to identity X if it equals X followed by "." followed by
 * the extension with its leading dot removed. A .desktop file or MIME-package file
 * (Linux) belongs to the identity equal to its own base name.
 *
 * Checks performed before any state is changed:
 *
 * 1. Every existing on-system identity for which belongsToFamily holds is collected -
 * Windows: every RegisteredApplications value name, and every name of a
 * Software\*\Capabilities key; Linux: every .desktop file base name, and every
 * MIME-package file base name. If more than one distinct such identity is found, the
 * operation refuses, naming all of them, and changes nothing.
 *
 * 2. [Windows only] For every extension in extensions,
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\(extension)\UserChoice
 * is read. If a value is present there for any of them - naming any ProgId at all,
 * including one belonging to identity - the operation refuses, naming every such
 * extension. The error states that no program can remove or change this value, and that
 * Settings, Apps, Default apps, Reset is the only way to clear it, and that Reset
 * affects every app default on the machine. Nothing is changed.
 *
 * 3. For every extension in extensions, its current claimants are determined - Windows:
 * the ProgId named by Classes\(extension) (default value), plus every ProgId named
 * under Classes\(extension)\OpenWithProgids; Linux: every .desktop file whose MimeType=
 * line names that extension's MIME type, plus every choice-file entry naming that type.
 * Each claimant is classified by whether its owning identity satisfies belongsToFamily.
 * A claimant whose owning identity does not satisfy belongsToFamily causes the operation
 * to refuse, naming the extension and the claimant, and changes nothing. A claimant
 * whose owning identity does satisfy belongsToFamily is marked for removal.
 *
 * 4. Every location identity would need to write to, and every location an identity
 * marked for removal would need to be removed from, is confirmed writable - Windows: no
 * additional check, registry keys under HKCU are always removable by their owner;
 * Linux: every such .desktop file, MIME-package file, and (when the whole file would be
 * deleted) its containing directory. If any are not writable, the operation refuses,
 * naming all of them.
 *
 * What happens once all checks pass:
 *
 * 5. If exactly one identity was found in step 1, it equals identity, and it already
 * declares exactly extensions (same extensions, same per-extension icons), the same
 * command, and the same program icon, nothing further happens: this is a successful
 * call that changes nothing.
 *
 * 6. Otherwise, every identity marked for removal in steps 1 and 3 is deleted in full -
 * every registry key or file it created, for every extension it declared, including
 * extensions absent from the current extensions list.
 *
 * 7. If extensions is not empty, identity is created declaring exactly the extensions in
 * extensions. For each: its own file icon; command followed by the operating system's
 * own file-argument placeholder ("%1" on Windows, %f on Linux) as the open command.
 * programIco/programPng is recorded as identity's own application icon, independent of
 * the per-extension file icons. If extensions is empty, nothing is created: identity is
 * left entirely unregistered.
 *
 * 8. One shell or database refresh is issued for the whole of steps 6-7 combined -
 * SHChangeNotify once (Windows) or update-mime-database/update-desktop-database once
 * (Linux) - never per extension, never per intermediate write, and never at all when
 * step 5 applied.
 *
 * What is guaranteed, and what is not:
 *
 * - If any check in steps 1-4 fails, no registry key or file has been touched.
 * - If step 6 or 7 fails partway through because the underlying OS write itself fails,
 * the system may be left with neither the old identity nor the complete new one
 * present; the resulting error reports this rather than asserting a specific residual
 * state.
 * - A successful return means identity declares exactly, and only, the extensions in
 * extensions - or, when extensions is empty, that identity is not registered at all.
 *
 * eradicateAll(belongsToFamily) performs none of the checks above: every existing
 * on-system identity for which belongsToFamily holds, however many there are, is
 * deleted in full, and nothing is created. One shell or database refresh is issued
 * for the whole operation, or none at all if no identity matched.
 */
public interface FileAssociations{
  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programIco, Path programPng,
      Function<String,RuntimeException> ambiguous,
      Function<String,RuntimeException> userLocked,
      Function<String,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    if (Fs.isWindows()){
      WindowsAssociations.reconcile(identity, belongsToFamily, command, extensions, programIco,
        ambiguous, userLocked, notOurs, notWritable, halfDone);
      return;
    }
    if (Fs.isLinux()){
      LinuxAssociations.reconcile(identity, belongsToFamily, command, extensions, programPng,
        ambiguous, notOurs, notWritable, halfDone);
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
