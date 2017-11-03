/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;

public class CodeLensHandler {

	private static final String JAVA_SHOW_REFERENCES_COMMAND = "java.show.references";
	private static final String JAVA_SHOW_IMPLEMENTATIONS_COMMAND = "java.show.implementations";
	private static final String JAVA_RUN_TEST_COMMAND = "java.run.test";
	private static final String JAVA_DEBUG_TEST_COMMAND = "java.debug.test";
	private static final String IMPLEMENTATION_TYPE = "implementations";
	private static final String REFERENCES_TYPE = "references";
	private static final String RUNTEST_TYPE = "runtest";
	private static final String DEBUGTEST_TYPE = "debugtest";

	private final PreferenceManager preferenceManager;

	public CodeLensHandler(PreferenceManager preferenceManager) {
		this.preferenceManager = preferenceManager;
	}

	@SuppressWarnings("unchecked")
	public CodeLens resolve(CodeLens lens, IProgressMonitor monitor) {
		if (lens == null) {
			return null;
		}
		//Note that codelens resolution is honored if the request was emitted
		//before disabling codelenses in the preferences, else invalid codeLenses
		//(i.e. having no commands) would be returned.
		List<Object> data = (List<Object>) lens.getData();
		String type = (String) data.get(2);
		Map<String, Object> position = (Map<String, Object>) data.get(1);
		String uri = (String) data.get(0);
		String label = null;
		String command = null;
		List<Location> locations = null;
		if (REFERENCES_TYPE.equals(type)) {
			label = "reference";
			command = JAVA_SHOW_REFERENCES_COMMAND;
		} else if (IMPLEMENTATION_TYPE.equals(type)) {
			label = "implementation";
			command = JAVA_SHOW_IMPLEMENTATIONS_COMMAND;
		} else if (RUNTEST_TYPE.equals(type)) {
			command = JAVA_RUN_TEST_COMMAND;
		} else if (DEBUGTEST_TYPE.equals(type)) {
			command = JAVA_DEBUG_TEST_COMMAND;
		}
		try {
			ICompilationUnit unit = JDTUtils.resolveCompilationUnit(uri);
			if (unit != null) {
				IJavaElement element = JDTUtils.findElementAtSelection(unit, ((Double) position.get("line")).intValue(), ((Double) position.get("character")).intValue(), this.preferenceManager, monitor);
				if (REFERENCES_TYPE.equals(type)) {
					try {
						locations = findReferences(element, monitor);
					} catch (CoreException e) {
						JavaLanguageServerPlugin.logException(e.getMessage(), e);
					}
				} else if (IMPLEMENTATION_TYPE.equals(type)) {
					if (element instanceof IType) {
						try {
							locations = findImplementations((IType) element, monitor);
						} catch (CoreException e) {
							JavaLanguageServerPlugin.logException(e.getMessage(), e);
						}
					}
				} else if (RUNTEST_TYPE.equals(type) || DEBUGTEST_TYPE.equals(type)) {
					List<String> suite = new ArrayList<>();
					if (element instanceof IType) {
						suite.add(((IType) element).getFullyQualifiedName());
					} else {
						String parent = ((IType) element.getParent()).getFullyQualifiedName();
						suite.add(parent + "#" + element.getElementName());
					}
					String[] classpaths = JavaRuntime.computeDefaultRuntimeClassPath(unit.getJavaProject());
					Command c = new Command(RUNTEST_TYPE.equals(type) ? "Run Test" : "Debug Test", command, Arrays.asList(uri, classpaths, suite));
					lens.setCommand(c);
					return lens;
				}
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("Problem resolving code lens", e);
		}
		if (locations == null) {
			locations = Collections.emptyList();
		}
		if (label != null && command != null) {
			int size = locations.size();
			Command c = new Command(size + " " + label + ((size == 1) ? "" : "s"), command, Arrays.asList(uri, position, locations));
			lens.setCommand(c);
		}
		return lens;
	}

	private List<Location> findImplementations(IType type, IProgressMonitor monitor) throws JavaModelException {
		IType[] results = type.newTypeHierarchy(monitor).getAllSubtypes(type);
		final List<Location> result = new ArrayList<>();
		for (IType t : results) {
			ICompilationUnit compilationUnit = (ICompilationUnit) t.getAncestor(IJavaElement.COMPILATION_UNIT);
			if (compilationUnit == null) {
				continue;
			}
			Location location = JDTUtils.toLocation(t);
			result.add(location);
		}
		return result;
	}

	private List<Location> findReferences(IJavaElement element, IProgressMonitor monitor)
			throws JavaModelException, CoreException {
		if (element == null) {
			return Collections.emptyList();
		}
		SearchPattern pattern = SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES);
		final List<Location> result = new ArrayList<>();
		SearchEngine engine = new SearchEngine();
		engine.search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
				createSearchScope(), new SearchRequestor() {

			@Override
			public void acceptSearchMatch(SearchMatch match) throws CoreException {
				Object o = match.getElement();
				if (o instanceof IJavaElement) {
					IJavaElement element = (IJavaElement) o;
					ICompilationUnit compilationUnit = (ICompilationUnit) element
							.getAncestor(IJavaElement.COMPILATION_UNIT);
					if (compilationUnit == null) {
						return;
					}
					Location location = JDTUtils.toLocation(compilationUnit, match.getOffset(), match.getLength());
					result.add(location);
				}
			}
		}, monitor);

		return result;
	}

	public List<CodeLens> getCodeLensSymbols(String uri, IProgressMonitor monitor) {
		if (!preferenceManager.getPreferences().isCodeLensEnabled()) {
			return Collections.emptyList();
		}
		final ICompilationUnit unit = JDTUtils.resolveCompilationUnit(uri);
		if (unit == null || !unit.getResource().exists() || monitor.isCanceled()) {
			return Collections.emptyList();
		}
		try {
			IJavaElement[] elements = unit.getChildren();
			ArrayList<CodeLens> lenses = new ArrayList<>(elements.length);
			collectCodeLenses(unit, elements, lenses, monitor);
			collectCodeLensesForJunit(unit, elements, lenses, monitor);
			if (monitor.isCanceled()) {
				lenses.clear();
			}
			return lenses;
		} catch (JavaModelException e) {
			JavaLanguageServerPlugin.logException("Problem getting code lenses for" + unit.getElementName(), e);
		}
		return Collections.emptyList();
	}

	private void collectCodeLenses(ICompilationUnit unit, IJavaElement[] elements, ArrayList<CodeLens> lenses,
			IProgressMonitor monitor)
			throws JavaModelException {
		for (IJavaElement element : elements) {
			if (monitor.isCanceled()) {
				return;
			}
			if (element.getElementType() == IJavaElement.TYPE) {
				collectCodeLenses(unit, ((IType) element).getChildren(), lenses, monitor);
			} else if (element.getElementType() != IJavaElement.METHOD || JDTUtils.isHiddenGeneratedElement(element)) {
				continue;
			}

			if (preferenceManager.getPreferences().isReferencesCodeLensEnabled()) {
				CodeLens lens = getCodeLens(REFERENCES_TYPE, element, unit);
				lenses.add(lens);
			}
			if (preferenceManager.getPreferences().isImplementationsCodeLensEnabled() && element instanceof IType) {
				IType type = (IType) element;
				if (type.isInterface() || Flags.isAbstract(type.getFlags())) {
					CodeLens lens = getCodeLens(IMPLEMENTATION_TYPE, element, unit);
					lenses.add(lens);
				}
			}
		}
	}

	private boolean collectCodeLensesForJunit(ICompilationUnit unit, IJavaElement[] elements, ArrayList<CodeLens> lenses, IProgressMonitor monitor) throws JavaModelException {
		if (!preferenceManager.getPreferences().isRunTestsCodeLensEnabled() && !preferenceManager.getPreferences().isDebugTestsCodeLensEnabled()) {
			return false;
		}
		boolean hasTests = false;
		for (IJavaElement element : elements) {
			if (monitor.isCanceled()) {
				return false;
			}
			if (element.getElementType() == IJavaElement.TYPE && ((IType) element).isClass()) {
				boolean res = collectCodeLensesForJunit(unit, ((IType) element).getChildren(), lenses, monitor);
				if (res) {
					if (preferenceManager.getPreferences().isRunTestsCodeLensEnabled()) {
						lenses.add(getCodeLens(RUNTEST_TYPE, element, unit));
						hasTests = true;
					}
					if (preferenceManager.getPreferences().isDebugTestsCodeLensEnabled()) {
						lenses.add(getCodeLens(DEBUGTEST_TYPE, element, unit));
						hasTests = true;
					}
				}
			} else if (element.getElementType() == IJavaElement.METHOD && !JDTUtils.isHiddenGeneratedElement(element)) {
				IMethod method = (IMethod) element;
				if (isTestMethod(method, "org.junit.Test")) {
					if (preferenceManager.getPreferences().isRunTestsCodeLensEnabled()) {
						lenses.add(getCodeLens(RUNTEST_TYPE, element, unit));
						hasTests = true;
					}
					if (preferenceManager.getPreferences().isDebugTestsCodeLensEnabled()) {
						lenses.add(getCodeLens(DEBUGTEST_TYPE, element, unit));
						hasTests = true;
					}
				}
			}
		}
		return hasTests;
	}

	private static boolean isTestMethod(IMethod method, String annotation) {
		int flags;
		try {
			flags = method.getFlags();
			// 'V' is void signature
			return !(method.isConstructor() || !Flags.isPublic(flags) || Flags.isAbstract(flags) || Flags.isStatic(flags) || !"V".equals(method.getReturnType())) && method.getAnnotation(annotation) != null;
		} catch (JavaModelException e) {
			// ignore
			return false;
		}
	}

	private CodeLens getCodeLens(String type, IJavaElement element, ICompilationUnit unit) throws JavaModelException {
		CodeLens lens = new CodeLens();
		ISourceRange r = ((ISourceReference) element).getNameRange();
		final Range range = JDTUtils.toRange(unit, r.getOffset(), r.getLength());
		lens.setRange(range);
		String uri = ResourceUtils.toClientUri(JDTUtils.getFileURI(unit));
		lens.setData(Arrays.asList(uri, range.getStart(), type));
		return lens;
	}

	private IJavaSearchScope createSearchScope() throws JavaModelException {
		IJavaProject[] projects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
		return SearchEngine.createJavaSearchScope(projects, IJavaSearchScope.SOURCES);
	}
}
