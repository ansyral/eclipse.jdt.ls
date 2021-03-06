/*******************************************************************************
* Copyright (c) 2017 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package org.eclipse.jdt.ls.debug.internal.core;

public interface IDebugEventHub {
    public void fireDebugEventSet(IDebugEvent[] events);

    public void addDebugEventSetListener(IDebugEventSetListener listener);

    public void removeDebugEventSetListener(IDebugEventSetListener listener);
}
