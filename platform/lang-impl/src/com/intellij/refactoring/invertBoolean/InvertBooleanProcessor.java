/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.refactoring.invertBoolean;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.refactoring.util.MoveRenameUsageInfo;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * @author ven
 */
public class InvertBooleanProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.invertBoolean.InvertBooleanMethodProcessor");
  private final InvertBooleanDelegate myDelegate;

  private PsiElement myElement;
  private final String myNewName;
  private final RenameProcessor myRenameProcessor;
  private final Map<UsageInfo, SmartPsiElementPointer> myToInvert = new HashMap<UsageInfo, SmartPsiElementPointer>();
  private final SmartPointerManager mySmartPointerManager;

  public InvertBooleanProcessor(final PsiElement namedElement, final String newName) {
    super(namedElement.getProject());
    myElement = namedElement;
    myNewName = newName;
    final Project project = namedElement.getProject();
    myRenameProcessor = !(namedElement instanceof PsiNamedElement) || Comparing.equal(((PsiNamedElement)namedElement).getName(), myNewName) 
                        ? null : new RenameProcessor(project, namedElement, newName, false, false);
    mySmartPointerManager = SmartPointerManager.getInstance(project);
    myDelegate = findInvertBooleanDelegate(myElement);
  }

  private static InvertBooleanDelegate findInvertBooleanDelegate(PsiElement element) {
    for (InvertBooleanDelegate delegate : Extensions.getExtensions(InvertBooleanDelegate.EP_NAME)) {
      if (delegate.isVisibleOnElement(element)) {
        return delegate;
      }
    }
    LOG.error(element);
    return null;
  }

  @Override
  @NotNull
  protected UsageViewDescriptor createUsageViewDescriptor(@NotNull UsageInfo[] usages) {
    return new InvertBooleanUsageViewDescriptor(myElement);
  }

  @Override
  protected boolean preprocessUsages(@NotNull Ref<UsageInfo[]> refUsages) {
    final MultiMap<PsiElement, String> conflicts = new MultiMap<PsiElement, String>();
    myDelegate.findConflicts(conflicts, refUsages.get());
    
    if (!conflicts.isEmpty())  {
      return showConflicts(conflicts, null);
    }

    if (myRenameProcessor == null || myRenameProcessor.preprocessUsages(refUsages)) {
      prepareSuccessful();
      return true;
    }
    return false;
  }

  @Override
  @NotNull
  protected UsageInfo[] findUsages() {
    final List<SmartPsiElementPointer> toInvert = new ArrayList<SmartPsiElementPointer>();

    final LinkedHashSet<PsiElement> elementsToInvert = new LinkedHashSet<PsiElement>();
    myDelegate.collectRefElements(myElement, elementsToInvert, myRenameProcessor, myNewName);
    for (PsiElement element : elementsToInvert) {
      toInvert.add(mySmartPointerManager.createSmartPsiElementPointer(element));
    }

    final UsageInfo[] renameUsages = myRenameProcessor != null ? myRenameProcessor.findUsages() : UsageInfo.EMPTY_ARRAY;

    final SmartPsiElementPointer[] usagesToInvert = toInvert.toArray(new SmartPsiElementPointer[toInvert.size()]);

    //merge rename and invert usages
    Map<PsiElement, UsageInfo> expressionsToUsages = new HashMap<PsiElement, UsageInfo>();
    List<UsageInfo> result = new ArrayList<UsageInfo>();
    for (UsageInfo renameUsage : renameUsages) {
      expressionsToUsages.put(renameUsage.getElement(), renameUsage);
      result.add(renameUsage);
    }

    for (SmartPsiElementPointer pointer : usagesToInvert) {
      final PsiElement expression = pointer.getElement();
      if (!expressionsToUsages.containsKey(expression)) {
        final UsageInfo usageInfo = new UsageInfo(expression);
        expressionsToUsages.put(expression, usageInfo);
        result.add(usageInfo); //fake UsageInfo
        myToInvert.put(usageInfo, pointer);
      } else {
        myToInvert.put(expressionsToUsages.get(expression), pointer);
      }
    }

    return result.toArray(new UsageInfo[result.size()]);
  }

  @Override
  protected void refreshElements(@NotNull PsiElement[] elements) {
    myElement = elements[0];
  }

  private static UsageInfo[] extractUsagesForElement(PsiElement element, UsageInfo[] usages) {
    final ArrayList<UsageInfo> extractedUsages = new ArrayList<UsageInfo>(usages.length);
    for (UsageInfo usage : usages) {
      if (usage instanceof MoveRenameUsageInfo) {
        MoveRenameUsageInfo usageInfo = (MoveRenameUsageInfo)usage;
        if (element.equals(usageInfo.getReferencedElement())) {
          extractedUsages.add(usageInfo);
        }
      }
    }
    return extractedUsages.toArray(new UsageInfo[extractedUsages.size()]);
  }


  @Override
  protected void performRefactoring(@NotNull UsageInfo[] usages) {
    if (myRenameProcessor != null) {
      for (final PsiElement element : myRenameProcessor.getElements()) {
        try {
          RenameUtil.doRename(element, myRenameProcessor.getNewName(element), extractUsagesForElement(element, usages), myProject, null);
        }
        catch (final IncorrectOperationException e) {
          RenameUtil.showErrorMessage(e, element, myProject);
          return;
        }
      }
    }


    for (UsageInfo usage : usages) {
      final SmartPsiElementPointer pointerToInvert = myToInvert.get(usage);
      if (pointerToInvert != null) {
        PsiElement element = pointerToInvert.getElement();
        LOG.assertTrue(element != null);
        try {
          myDelegate.replaceWithNegatedExpression(element);
        }
        catch (IncorrectOperationException e) {
          LOG.error(e);
        }
      }
    }

    myDelegate.invertDefaultElementInitializer(myElement);
  }

  @Override
  protected String getCommandName() {
    return InvertBooleanHandler.REFACTORING_NAME;
  }
}
