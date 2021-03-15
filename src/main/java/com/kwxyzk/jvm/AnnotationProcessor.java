/*
 * @author kongweixiang
 * @version 1.0.0
 */
package com.kwxyzk.jvm;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * @author kongweixiang
 * @date 2020/12/16
 * @since 1.0.0
 */
@SupportedAnnotationTypes({"com.kwxyzk.jvm.Create"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class AnnotationProcessor extends AbstractProcessor {
    private boolean initialized = false;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        roundEnv.getRootElements();
        return false;
    }
}
