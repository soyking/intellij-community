/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.ide.ui.search;

import com.intellij.application.options.OptionsContainingConfigurable;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.impl.AllFileTemplatesConfigurable;
import com.intellij.ide.plugins.AvailablePluginsManagerMain;
import com.intellij.ide.plugins.PluginManagerConfigurable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarterEx;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.keymap.impl.ui.KeymapPanel;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.options.ex.ConfigurableWrapper;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtil;
import org.jdom.Document;
import org.jdom.Element;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Used in installer's "build searchable options" step.
 *
 * In order to run locally, use "TraverseUi" run configuration (pass corresponding "idea.platform.prefix" property via VM options,
 * and choose correct main module).
 */
@SuppressWarnings({"CallToPrintStackTrace", "SynchronizeOnThis", "UseOfSystemOutOrSystemErr"})
public class TraverseUIStarter extends ApplicationStarterEx {
  private static final String OPTIONS = "options";
  private static final String CONFIGURABLE = "configurable";
  private static final String ID = "id";
  private static final String CONFIGURABLE_NAME = "configurable_name";
  private static final String OPTION = "option";
  private static final String NAME = "name";
  private static final String PATH = "path";
  private static final String HIT = "hit";

  private String OUTPUT_PATH;

  @Override
  public boolean isHeadless() {
    return true;
  }

  @Override
  public String getCommandName() {
    return "traverseUI";
  }

  @Override
  public void premain(String[] args) {
    OUTPUT_PATH = args[1];
  }

  @Override
  public void main(String[] args) {
    System.out.println("Starting searchable options index builder");
    try {
      startup(OUTPUT_PATH);
      ((ApplicationEx)ApplicationManager.getApplication()).exit(true, true);
    }
    catch (Throwable e) {
      System.out.println("Searchable options index builder failed");
      e.printStackTrace();
      System.exit(-1);
    }
  }

  public static void startup(String outputPath) throws IOException {
    Map<SearchableConfigurable, Set<OptionDescription>> options = new LinkedHashMap<>();
    SearchUtil.processProjectConfigurables(ProjectManager.getInstance().getDefaultProject(), options);

    Element root = new Element(OPTIONS);
    for (SearchableConfigurable option : options.keySet()) {
      SearchableConfigurable configurable = option;

      Element configurableElement = new Element(CONFIGURABLE);
      String id = configurable.getId();
      configurableElement.setAttribute(ID, id);
      configurableElement.setAttribute(CONFIGURABLE_NAME, configurable.getDisplayName());
      Set<OptionDescription> sortedOptions = options.get(configurable);
      writeOptions(configurableElement, sortedOptions);

      if (configurable instanceof ConfigurableWrapper) {
        UnnamedConfigurable wrapped = ((ConfigurableWrapper)configurable).getConfigurable();
        if (wrapped instanceof SearchableConfigurable) {
          configurable = (SearchableConfigurable)wrapped;
        }
      }
      if (configurable instanceof KeymapPanel) {
        processKeymap(configurableElement);
      }
      else if (configurable instanceof OptionsContainingConfigurable) {
        processOptionsContainingConfigurable((OptionsContainingConfigurable)configurable, configurableElement);
      }
      else if (configurable instanceof PluginManagerConfigurable) {
        for (OptionDescription description : wordsToOptionDescriptors(Collections.singleton(AvailablePluginsManagerMain.MANAGE_REPOSITORIES))) {
          append(null, AvailablePluginsManagerMain.MANAGE_REPOSITORIES, description.getOption(), configurableElement);
        }
      }
      else if (configurable instanceof AllFileTemplatesConfigurable) {
        processFileTemplates(configurableElement);
      }

      root.addContent(configurableElement);
      configurable.disposeUIResources();
    }

    FileUtil.ensureCanCreateFile(new File(outputPath));
    JDOMUtil.writeDocument(new Document(root), outputPath, "\n");

    System.out.println("Searchable options index builder completed");
  }

  private static void processFileTemplates(Element configurableElement) {
    SearchableOptionsRegistrar optionsRegistrar = SearchableOptionsRegistrar.getInstance();
    Set<OptionDescription> options = new TreeSet<>();

    FileTemplateManager fileTemplateManager = FileTemplateManager.getDefaultInstance();
    processTemplates(optionsRegistrar, options, fileTemplateManager.getAllTemplates());
    processTemplates(optionsRegistrar, options, fileTemplateManager.getAllPatterns());
    processTemplates(optionsRegistrar, options, fileTemplateManager.getAllCodeTemplates());
    processTemplates(optionsRegistrar, options, fileTemplateManager.getAllJ2eeTemplates());

    writeOptions(configurableElement, options);
  }

  private static void processTemplates(SearchableOptionsRegistrar registrar, Set<OptionDescription> options, FileTemplate[] templates) {
    for (FileTemplate template : templates) {
      collectOptions(registrar, options, template.getName(), null);
    }
  }

  private static void collectOptions(SearchableOptionsRegistrar registrar, Set<OptionDescription> options, String text, String path) {
    for (String word : registrar.getProcessedWordsWithoutStemming(text)) {
      options.add(new OptionDescription(word, text, path));
    }
  }

  private static void processOptionsContainingConfigurable(OptionsContainingConfigurable configurable, Element configurableElement) {
    Set<String> optionsPath = configurable.processListOptions();
    Set<OptionDescription> result = wordsToOptionDescriptors(optionsPath);
    writeOptions(configurableElement, result);
  }

  private static Set<OptionDescription> wordsToOptionDescriptors(Set<String> optionsPath) {
    SearchableOptionsRegistrar registrar = SearchableOptionsRegistrar.getInstance();
    Set<OptionDescription> result = new TreeSet<>();
    for (String opt : optionsPath) {
      for (String word : registrar.getProcessedWordsWithoutStemming(opt)) {
        if (word != null) {
          result.add(new OptionDescription(word, opt, null));
        }
      }
    }
    return result;
  }

  private static void processKeymap(Element configurableElement) {
    final ActionManager actionManager = ActionManager.getInstance();
    final String componentName = actionManager.getComponentName();
    final SearchableOptionsRegistrar searchableOptionsRegistrar = SearchableOptionsRegistrar.getInstance();
    final Set<String> ids = ((ActionManagerImpl)actionManager).getActionIds();
    final TreeSet<OptionDescription> options = new TreeSet<>();
    for (String id : ids) {
      final AnAction anAction = actionManager.getAction(id);
      final String text = anAction.getTemplatePresentation().getText();
      if (text != null) {
        collectOptions(searchableOptionsRegistrar, options, text, componentName);
      }
      final String description = anAction.getTemplatePresentation().getDescription();
      if (description != null) {
        collectOptions(searchableOptionsRegistrar, options, description, componentName);
      }
    }
    writeOptions(configurableElement, options);
  }

  private static void writeOptions(Element configurableElement, Set<OptionDescription> options) {
    for (OptionDescription opt : options) {
      append(opt.getPath(), opt.getHit(), opt.getOption(), configurableElement);
    }
  }

  private static void append(String path, String hit, final String word, final Element configurableElement) {
    Element optionElement = new Element(OPTION);
    optionElement.setAttribute(NAME, word);
    if (path != null) {
      optionElement.setAttribute(PATH, path);
    }
    optionElement.setAttribute(HIT, hit);
    configurableElement.addContent(optionElement);
  }
}