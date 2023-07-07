package com.dguner.lombokbuilderhelper;

import com.intellij.codeInsight.NullableNotNullManager;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.impl.source.tree.java.PsiIdentifierImpl;
import com.intellij.psi.impl.source.tree.java.PsiMethodCallExpressionImpl;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiUtil;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class LombokBuilderInspection extends AbstractBaseJavaLocalInspectionTool {
    public static final String QUICK_FIX_NAME = "Add all mandatory fields";
    private static final Logger LOG =
            Logger.getInstance("#com.dguner.lombokbuilderhelper.LombokBuilderInspection");
    private final LbiQuickFix myQuickFix = new LbiQuickFix();

    private List<String> processMissingFields(PsiElement expression, List<String> mandatoryFields) {
        Queue<PsiElement> queue = new LinkedList<>();
        Set<PsiElement> seenElements = new HashSet<>();
        queue.offer(expression);

        while (!queue.isEmpty()) {
            PsiElement cur = queue.poll();
            if (cur != null) {
                seenElements.add(cur);
                if (cur instanceof PsiIdentifierImpl) {
                    mandatoryFields.remove(cur.getText());
                }

                if (cur instanceof PsiMethodCallExpressionImpl) {
                    PsiMethod resolvedMethod = ((PsiMethodCallExpressionImpl) cur).resolveMethod();
                    if (resolvedMethod != null) {
                        // If the resolved method is not a lombok method, add the return statement to the queue
                        // to visit its nodes too
                        if (!Objects.equals(resolvedMethod.getClass().getName(),
                                "de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder")) {
                            for (PsiReturnStatement returnStatement : PsiUtil.findReturnStatements(
                                    resolvedMethod)) {
                                queue.offer(returnStatement.getReturnValue());
                            }
                            // If we are calling build on an element that was a result of a toBuilder call we assume
                            // that the builder already has all mandatory fields set
                        } else if (Objects.equals(resolvedMethod.getName(), "toBuilder")) {
                            mandatoryFields.clear();
                            break;
                        }
                    }
                }

                if (cur instanceof PsiReferenceExpressionImpl) {
                    PsiElement resolvedElement = ((PsiReferenceExpressionImpl) cur).resolve();
                    if (resolvedElement instanceof PsiLocalVariable) {
                        PsiElement initializer =
                                ((PsiLocalVariable) resolvedElement).getInitializer();
                        if (!seenElements.contains(initializer)) {
                            queue.offer(initializer);
                        }

                        Arrays.stream(ReferencesSearch.search(resolvedElement,
                                GlobalSearchScope.fileScope(resolvedElement.getContainingFile()),
                                false).toArray(PsiReference.EMPTY_ARRAY)).forEach(reference -> {
                            if (reference.getElement().getTextRange().getStartOffset()
                                    < cur.getTextRange().getStartOffset()) {
                                PsiElement referenceParent = reference.getElement().getParent();
                                if (!seenElements.contains(referenceParent)) {
                                    queue.offer(referenceParent);
                                }
                            }
                        });
                    }
                }

                for (PsiElement child : cur.getChildren()) {
                    if (!seenElements.contains(child) && (child instanceof PsiIdentifierImpl
                            || child instanceof PsiMethodCallExpressionImpl
                            || child instanceof PsiReferenceExpressionImpl)) {
                        queue.offer(child);
                    }
                }
            }
        }

        return mandatoryFields;
    }

    private PsiClass getContainingBuilderClass(PsiMethod element) {
        PsiClass aClass = element.getContainingClass();
        while (aClass != null && !isClassBuilder(aClass)) {
            aClass = aClass.getContainingClass();
        }

        return aClass;
    }

    private boolean isClassBuilder(PsiClass aClass) {
        final Set<String> builderClassQualifiedNames =
                Set.of("lombok.Builder", "lombok.experimental.SuperBuilder");
        return Arrays.stream(aClass.getAnnotations())
                .anyMatch(annotation -> builderClassQualifiedNames.contains(
                        annotation.getQualifiedName()));
    }

    private List<String> getMandatoryFields(PsiClass aClass, @NotNull Project project) {
        final List<String> nonNullAnnotations = project.getComponent(NullableNotNullManager.class).getNotNulls();
        final String defaultBuilderValueAnnotation = "lombok.Builder.Default";
        return Arrays.stream(aClass.getAllFields()).filter(field -> {
            final PsiAnnotation[] annotations = field.getAnnotations();
            final PsiModifierList modifiers = field.getModifierList();
            final boolean isPrimitiveType = false; // field.getType() instanceof PsiPrimitiveType;
            final boolean isStaticField =
                    modifiers != null && modifiers.hasModifierProperty(PsiModifier.STATIC);
            return !isStaticField && (isPrimitiveType || Arrays.stream(annotations)
                    .anyMatch(annotation -> nonNullAnnotations.contains(
                            annotation.getQualifiedName()))) && Arrays.stream(annotations)
                    .noneMatch(annotation -> Objects.equals(annotation.getQualifiedName(),
                            defaultBuilderValueAnnotation));
        }).map(PsiField::getName).collect(Collectors.toList());
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            private static final String DESCRIPTION_TEMPLATE =
                    "Lombok builder is missing non nullable fields";

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiMethod resolvedMethod = expression.resolveMethod();

                if (resolvedMethod != null && Objects.equals(resolvedMethod.getClass().getName(),
                        "de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder")
                        && Objects.equals(resolvedMethod.getName(), "build")) {
                    PsiClass builderClass = getContainingBuilderClass(resolvedMethod);
                    if (builderClass != null && processMissingFields(expression,
                            getMandatoryFields(builderClass, expression.getProject())).size() > 0) {
                        holder.registerProblem(expression, DESCRIPTION_TEMPLATE,
                                ProblemHighlightType.GENERIC_ERROR, myQuickFix);
                    }
                }
            }
        };
    }

    private class LbiQuickFix implements LocalQuickFix {
        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethodCallExpression expression =
                    (PsiMethodCallExpression) descriptor.getPsiElement();
            PsiMethod resolvedMethod = expression.resolveMethod();

            if (resolvedMethod != null) {
                List<String> missingFields = processMissingFields(expression,
                        getMandatoryFields(getContainingBuilderClass(resolvedMethod), project));

                if (!missingFields.isEmpty()) {
                    String errorText = expression.getText();

                    PsiElementFactory factory =
                            JavaPsiFacade.getInstance(project).getElementFactory();
                    PsiMethodCallExpression fixedMethodExpression =
                            (PsiMethodCallExpression) factory.createExpressionFromText(
                                    errorText.replace(".build()",
                                            "." + String.join("().", missingFields) + "().build()"),
                                    null);

                    expression.replace(fixedMethodExpression);
                }
            } else {
                LOG.error("Resolved method null when applying fix");
            }
        }

        @NotNull
        public String getFamilyName() {
            return QUICK_FIX_NAME;
        }
    }
}
