package com.googlecode.goclipse.builder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.preference.IPreferenceStore;

import com.googlecode.goclipse.Activator;
import com.googlecode.goclipse.Environment;
import com.googlecode.goclipse.preferences.PreferenceConstants;
import com.googlecode.goclipse.utils.ObjectUtils;

/**
 * GoCompiler provides the GoClipse interface to the go build tool.
 */
public class GoCompiler {

	private static final QualifiedName	COMPILER_VERSION_QN	= new QualifiedName(Activator.PLUGIN_ID, "compilerVersion");

	/** environment for build */
	private Map<String, String>  env;
	private String	             version;
	private long	             versionLastUpdated	= 0;
	
	/**
	 * 
	 */
	public GoCompiler() {}
	
	/**
	 * Returns the current compiler version (ex. "6g version release.r58 8787").
	 * Returns null if we were unable to recover the compiler version. The
	 * returned string should be treated as an opaque token.
	 * 
	 * @return the current compiler version
	 */
	public static String getCompilerVersion() {
		
		String version = null;
		IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
		String compilerPath = preferenceStore.getString(PreferenceConstants.GO_TOOL_PATH);

		if (compilerPath == null || compilerPath.length() == 0) {
			return null;
		}

		try {
			String[] cmd = { compilerPath, GoConstants.GO_VERSION_COMMAND };

			Runtime runtime = Runtime.getRuntime();
			Process p = runtime.exec(cmd);

			try {
				p.waitFor();

			} catch (InterruptedException e) {
				Activator.logInfo(e);
			}

			InputStream is = p.getInputStream();
			InputStream es = p.getErrorStream();
			StreamAsLines output = new StreamAsLines();
			StreamAsLines errors = new StreamAsLines();
			output.process(is);
			errors.process(es);

			final String GO_VERSION = "go version ";

			for (String line : output.getLines()) {
				if (line != null && line.startsWith(GO_VERSION)) {
					version = line.substring(GO_VERSION.length());
					break;
				}
			}

		} catch (IOException e) {
			Activator.logInfo(e);
		}

		return version;
	}

	/**
	 * @param project
	 * @param target
	 */
	public void goGetDependencies(final IProject project, java.io.File target) {
		Activator.logInfo("Dependencies ->"+project+":"+target);
		final IPath projectLocation = project.getLocation();
		final IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
		final String compilerPath = preferenceStore.getString(PreferenceConstants.GO_TOOL_PATH);

		try {
			String[] cmd = { compilerPath, GoConstants.GO_GET_COMMAND, target.getAbsolutePath() };

			String  goPath  = buildGoPath(projectLocation);
			String  PATH    = System.getenv("PATH");
			Runtime runtime = Runtime.getRuntime();
			Process p       = runtime.exec(cmd, new String[] { "GOPATH=" + goPath, "PATH="+PATH }, target.getParentFile());

			try {
				p.waitFor();

			} catch (InterruptedException e) {
				Activator.logInfo(e);
				
			}

			InputStream is = p.getInputStream();
			InputStream es = p.getErrorStream();
			StreamAsLines sal = new StreamAsLines();
			sal.process(is);
			sal.process(es);
			Activator.logInfo(sal.getLinesAsString());

		} catch (IOException e1) {
			Activator.logInfo(e1);
			
		}
	}

	/**
	 * @param projectLocation
	 * @return
	 */
	public static String buildGoPath(final IPath projectLocation) {
		String goPath = projectLocation.toOSString();
		final String SYSTEM_GO_PATH = System.getenv("GOPATH");

		if (SYSTEM_GO_PATH != null) {
			goPath = SYSTEM_GO_PATH + ":" + goPath;
		}

		return goPath;
	}

	/**
	 * @param project
	 * @param pmonitor
	 * @param fileList
	 */
	public void compileCmd(final IProject project, IProgressMonitor pmonitor, java.io.File target) {
		
		final IPath  projectLocation = project.getLocation();
		final IFile  file            = project.getFile(target.getAbsolutePath().replace(projectLocation.toOSString(), ""));
		final String pkgPath         = target.getParentFile().getAbsolutePath().replace(projectLocation.toOSString(), "");
		
		final IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
		final String           compilerPath    = preferenceStore.getString(PreferenceConstants.GO_TOOL_PATH);
		
		IPath binFolder = Environment.INSTANCE.getBinOutputFolder();
		
		String PATH = System.getenv("PATH");

		try {
			String[] cmd = {
			        compilerPath,
			        GoConstants.GO_BUILD_COMMAND,
			        GoConstants.COMPILER_OPTION_O,
			        projectLocation.toOSString() + "/" + binFolder + File.separator
			                + target.getName().replace(GoConstants.GO_SOURCE_FILE_EXTENSION, ""), file.getName() };

			String goPath = buildGoPath(projectLocation);

			Runtime runtime = Runtime.getRuntime();
			Process p = runtime.exec(cmd, new String[] { "GOPATH=" + goPath, "PATH="+PATH }, target.getParentFile());

			try {
				p.waitFor();

			} catch (InterruptedException e) {
				Activator.logInfo(e);
			}
			
			clearErrorMessages(file);

			InputStream is = p.getInputStream();
			InputStream es = p.getErrorStream();
			StreamAsLines sal = new StreamAsLines();
			sal.process(is);
			sal.process(es);
			
			if (sal.getLines().size() > 0) {
				processCompileOutput(sal, project, pkgPath, file);
			}

		} catch (IOException e1) {
			Activator.logInfo(e1);
		}
	}

	/**
     * @param file
     */
    private void clearErrorMessages(final IFile file) {
	    MarkerUtilities.deleteFileMarkers(file);
    }

    /**
     * 
     * @param output
     * @param project
     * @param relativeTargetDir
     */
	private void processCompileOutput(final StreamAsLines output,
			                          final IProject      project,
			                          final String        relativeTargetDir,
			                          final IFile         file) {
		
		for (String line : output.getLines()) {
			
			if (line.startsWith("#")) {
				continue;
			}
			
			int goPos = line.indexOf(GoConstants.GO_SOURCE_FILE_EXTENSION);

			if (goPos > 0) {
				int fileNameLength = goPos + GoConstants.GO_SOURCE_FILE_EXTENSION.length();

				// Strip the prefix off the error message
				String fileName = line.substring(0, fileNameLength);
				fileName = fileName.replace(project.getLocation().toOSString(), "");
				fileName = fileName.substring(fileName.indexOf(":") + 1).trim();

				// Determine the type of error message
				if (fileName.startsWith(File.separator)) {
					fileName = fileName.substring(1);
				} else if (fileName.startsWith("." + File.separator)) {
					fileName = relativeTargetDir.substring(1) + File.separator + fileName.substring(2);
				} else if (line.startsWith("can't")) {
					fileName = relativeTargetDir.substring(1);
				} else {
					fileName = relativeTargetDir.substring(1) + File.separator + fileName;
				}
				
				// find the resource if possible
				IResource resource = project.findMember(fileName);
				if (resource == null) {
					resource = project;
				}

				// Create the error message
				String msg = line.substring(fileNameLength + 1);
				String[] str = msg.split(":", 3);
				int location = -1; // marker for trouble
				int messageStart = msg.indexOf(": ");

				try {
					location = Integer.parseInt(str[0]);
				} catch (NumberFormatException nfe) {}
				
				// Determine how to mark the message
				if (location != -1 && messageStart != -1) {
					String message = msg.substring(messageStart + 2);
					MarkerUtilities.addMarker(resource, location, message, IMarker.SEVERITY_ERROR);
					
				} else {
					// play safe. to show something in UI
					MarkerUtilities.addMarker(resource, 1, line, IMarker.SEVERITY_ERROR);
				}
				
			} else {
				// runtime.main: undefined: main.main
				MarkerUtilities.addMarker(project, 1, line, IMarker.SEVERITY_ERROR);
				Activator.logError("unable to parse: " + line);
			}
		}
	}

	/**
	 * @param project
	 * @param pmonitor
	 * @param fileList
	 */
	public String compilePkg(final IProject project, IProgressMonitor pmonitor, String pkgpath, java.io.File target) {
		
		final IPath  projectLocation = project.getLocation();
		final IFile  file            = project.getFile(target.getAbsolutePath().replace(projectLocation.toOSString(), ""));
		final String pkgPath         = target.getParentFile().getAbsolutePath().replace(projectLocation.toOSString(), "");

		final IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
		final String           compilerPath    = preferenceStore.getString(PreferenceConstants.GO_TOOL_PATH);
		
		try {
			String[] cmd = { compilerPath, GoConstants.GO_BUILD_COMMAND, GoConstants.COMPILER_OPTION_O, pkgpath, "." };

			String goPath = buildGoPath(projectLocation);
			String PATH = System.getenv("PATH");
			Runtime runtime = Runtime.getRuntime();
			Process p = runtime.exec(cmd, new String[] { "GOPATH=" + goPath, "PATH="+PATH }, target.getParentFile());

			try {
				p.waitFor();

			} catch (InterruptedException e) {
				Activator.logInfo(e);
			}

			clearErrorMessages(file);

			InputStream is = p.getInputStream();
			InputStream es = p.getErrorStream();
			StreamAsLines sal = new StreamAsLines();
			sal.process(is);
			sal.process(es);

			if (sal.getLines().size() > 0) {
				processCompileOutput(sal, project, pkgPath, file);
			}

		} catch (IOException e1) {
			Activator.logInfo(e1);
		}

		return pkgpath;
	}

	/**
	 * 
	 * @param project
	 * @param pmonitor
	 * @param target
	 * @param dependencies
	 */
	public void compile(final IProject project, IProgressMonitor pmonitor, String target, String... dependencies) {
		final IFile file = project.getFile(new File(target).getAbsolutePath().replace(
		        project.getLocation().toOSString(), ""));

		if (!dependenciesExist(project, dependencies)) {
			Activator.logWarning("Missing dependency for '" + target + "' not compiling");
			return;
		}

		SubMonitor monitor = SubMonitor.convert(pmonitor, 130);
		Activator.logInfo("compile():" + dependencies);

		final IPath prjLoc = project.getLocation();
		IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
		String compilerPath = preferenceStore.getString(PreferenceConstants.GO_TOOL_PATH);
		// String goarch =
		// preferenceStore.getString(PreferenceConstants.GOARCH);
		IPath pkgPath = Environment.INSTANCE.getPkgOutputFolder(project);
		final ExternalCommand compilePackageCmd = new ExternalCommand(compilerPath);
		compilePackageCmd.setEnvironment(env);
		// get the architecture
		// Arch arch = Arch.getArch(goarch);
		StreamAsLines output = new StreamAsLines();
		output.setCombineLines(true);
		compilePackageCmd.setResultsFilter(output);

		Activator.logInfo("building " + target);
		List<String> args = new ArrayList<String>();

		args.clear();
		// show all errors option
		args.add(GoConstants.COMPILER_OPTION_E);
		// include folder
		args.add(GoConstants.COMPILER_OPTION_I);
		args.add(prjLoc.append(pkgPath).toOSString());
		// output file option
		args.add(GoConstants.COMPILER_OPTION_O);
		// output file
		args.add(target);

		compilePackageCmd.setWorkingFolder(prjLoc.toOSString());
		IResource firstDependency = null;
		//errorMessages.clear();
		for (String dependency : dependencies) {
			if (dependency.endsWith(GoConstants.GO_SOURCE_FILE_EXTENSION)) {
				IResource resource = project.findMember(dependency);
				if (firstDependency == null) {
					firstDependency = resource;
				}
				MarkerUtilities.deleteFileMarkers(resource);
				args.add(dependency);
			}
		}
		String result = compilePackageCmd.execute(args);
		if (result != null) {
			MarkerUtilities.addMarker(firstDependency, -1, result, IMarker.SEVERITY_ERROR);
		}
		processCompileOutput(output, project, "", file);

		try {
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		} catch (CoreException e) {
			Activator.logInfo(e);
		}
	}

	/**
	 * 
	 * @param goEnv
	 */
	public void setEnvironment(Map<String, String> goEnv) {
		this.env = goEnv;
	}

	/**
	 * 
	 * @return
	 */
	public String getVersion() {
		// The getCompilerVersion() call takes about ~30 msec to compute.
		// Because it's expensive,
		// we cache the value for a short time.
		final long TEN_SECONDS = 10 * 1000;

		if ((System.currentTimeMillis() - versionLastUpdated) > TEN_SECONDS) {
			version = getCompilerVersion();
			versionLastUpdated = System.currentTimeMillis();
		}

		return version;
	}

	/**
	 * 
	 * @param project
	 * @param dependencies
	 * @return
	 */
	private boolean dependenciesExist(IProject project, String[] dependencies) {
		ArrayList<String> sourceDependencies = new ArrayList<String>();
		for (String dependency : dependencies) {
			if (dependency.endsWith(GoConstants.GO_SOURCE_FILE_EXTENSION)) {
				sourceDependencies.add(dependency);
			}
		}
		return GoBuilder.dependenciesExist(project, sourceDependencies.toArray(new String[] {}));
	}

	/**
	 * 
	 * @param project
	 */
	public void updateVersion(IProject project) {
		try {
			project.setPersistentProperty(COMPILER_VERSION_QN, getVersion());
		} catch (CoreException ex) {
			Activator.logError(ex);
		}
	}

	/**
	 * 
	 * @param project
	 * @return
	 */
	public boolean requiresRebuild(IProject project) {
		String storedVersion;

		try {
			storedVersion = project.getPersistentProperty(COMPILER_VERSION_QN);
		} catch (CoreException ex) {
			storedVersion = null;
		}

		String currentVersion = getVersion();

		if (currentVersion == null) {
			// We were not able to get the latest compiler version - don't force
			// a rebuild.
			return false;
		} else {
			return !ObjectUtils.objectEquals(storedVersion, currentVersion);
		}
	}

}