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

import de.bmarwell.proximo.pitido.core.LanguageMenuConfig;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.SequencedMap;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@WebListener
public class LanguageInfoListener implements ServletContextListener {

    private final System.Logger logger = System.getLogger(LanguageInfoListener.class.getName());

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Inject
    @ConfigProperty(name = "sip.languages.enabled", defaultValue = "")
    String enabledLanguagesConfig;

    @Override
    public void contextInitialized(javax.servlet.ServletContextEvent sce) {
        if (this.languageFactories == null || this.languageFactories.isUnsatisfied()) {
            this.logger.log(System.Logger.Level.WARNING, "No language factories found.");
            return;
        }

        List<LanguageFactory> all = this.languageFactories.stream().toList();
        this.logger.log(Level.INFO, "Found {0} language factories: [{1}]", all.size(), all);

        SequencedMap<Integer, String> configured = LanguageMenuConfig.parse(this.enabledLanguagesConfig);

        if (configured.isEmpty()) {
            this.logger.log(Level.DEBUG, "sip.languages.enabled not set — all discovered factories are active.");
            return;
        }

        this.logger.log(Level.DEBUG, "sip.languages.enabled = [{0}]", this.enabledLanguagesConfig);

        for (LanguageFactory factory : all) {
            String tag = factory.locale().toLanguageTag();
            boolean active = configured.values().stream().anyMatch(tag::equals);

            if (active) {
                this.logger.log(Level.DEBUG, "  ACTIVE   [{0}] (locale tag: {1})", factory.displayName(), tag);
            } else {
                this.logger.log(
                        Level.DEBUG,
                        "  DROPPED  [{0}] (locale tag: {1}) — not in sip.languages.enabled",
                        factory.displayName(),
                        tag);
            }
        }
    }
}
