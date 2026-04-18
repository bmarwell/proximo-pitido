/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * ${PROJECT_HOME}/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 */
package de.bmarwell.proximo.pitido.war.listener;

import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import java.lang.System.Logger.Level;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@ApplicationScoped
@WebListener
public class LanguageInfoListener implements ServletContextListener {

    private final System.Logger logger = System.getLogger(LanguageInfoListener.class.getName());

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Override
    public void contextInitialized(javax.servlet.ServletContextEvent sce) {
        if (this.languageFactories == null || this.languageFactories.isUnsatisfied()) {
            this.logger.log(System.Logger.Level.WARNING, "No language factories found.");
            return;
        }

        this.logger.log(
                Level.INFO,
                "Found {0} language factories: [{1}]",
                this.languageFactories.stream().count(),
                this.languageFactories.stream().toList());
    }
}
