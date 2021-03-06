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

public interface IBreakpoint {
    public String getTypeName();

    public int getHitCount();

    public void setHitCount(int hitCount);

    public void addToVMTarget(IVMTarget vmTarget);

    public void removeFromVMTarget(IVMTarget vmTarget);

    public String getKey();
    
    public int getId();
    
    public boolean isVerified();
}
