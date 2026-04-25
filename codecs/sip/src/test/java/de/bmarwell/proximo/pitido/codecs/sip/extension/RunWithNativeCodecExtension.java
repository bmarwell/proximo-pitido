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
package de.bmarwell.proximo.pitido.codecs.sip.extension;

import de.bmarwell.proximo.pitido.codecs.sip.NativeRtpCodecFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public class RunWithNativeCodecExtension implements ExecutionCondition {

    ConditionEvaluationResult evaluate(NativeCodec annotation) {
        if (annotation == null) {
            return ConditionEvaluationResult.enabled("No @NativeCodec annotation");
        }

        final Class<? extends NativeRtpCodecFactory> value = annotation.value();

        final Optional<Constructor<?>> noArgConstructor = Stream.concat(
                        Arrays.stream(value.getDeclaredConstructors()), Arrays.stream(value.getConstructors()))
                .filter(constructor -> constructor.getParameterCount() == 0)
                .findFirst();

        if (noArgConstructor.isEmpty()) {
            throw new IllegalStateException("No no-arg constructor found for " + value.getName());
        }

        try {
            final NativeRtpCodecFactory newInstance =
                    (NativeRtpCodecFactory) noArgConstructor.get().newInstance();

            if (newInstance.isAvailable()) {
                return ConditionEvaluationResult.enabled("Native codec is available");
            }

        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            ConditionEvaluationResult.disabled("Cannot instantiate native codec: " + e.getMessage());
        }

        return ConditionEvaluationResult.disabled("Native codec is not available");
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (context.getTestMethod().isPresent()) {
            return evaluate(context.getRequiredTestMethod().getAnnotation(NativeCodec.class));
        }

        final Optional<Class<?>> testClass = context.getTestClass();

        if (testClass.isPresent()) {
            return evaluate(testClass.get().getAnnotation(NativeCodec.class));
        }

        return ConditionEvaluationResult.enabled("Not a test method or class");
    }
}
