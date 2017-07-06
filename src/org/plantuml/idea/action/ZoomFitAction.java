package org.plantuml.idea.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

public class ZoomFitAction extends ZoomAction {
    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        setZoom(project, getFitZoom(project));
    }
}
