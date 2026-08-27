package com.cc.springai.controller;

import com.cc.springai.skill.AgentSkillService;
import com.cc.springai.skill.AgentSkillFile;
import com.cc.springai.skill.AgentSkillSummary;
import com.cc.springai.skill.RenderedSkill;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/skills")
public class SkillController {

    private final AgentSkillService skillService;

    public SkillController(AgentSkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public List<AgentSkillSummary> list(@RequestParam String workingDirectory) {
        return skillService.listSkills(workingDirectory);
    }

    @GetMapping("/render")
    public RenderedSkill render(@RequestParam String workingDirectory,
                                @RequestParam String commandName,
                                @RequestParam(required = false) String arguments) {
        return skillService.renderSkill(workingDirectory, commandName, arguments)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + commandName));
    }

    @GetMapping("/file")
    public AgentSkillFile file(@RequestParam String workingDirectory,
                               @RequestParam String commandName) {
        return skillService.readSkillFile(workingDirectory, commandName);
    }

    @PutMapping("/file")
    public AgentSkillFile save(@RequestBody SkillFileRequest request) {
        return skillService.writeSkillFile(request.workingDirectory(), request.commandName(), request.content());
    }

    @DeleteMapping
    public DeleteSkillResponse delete(@RequestParam String workingDirectory,
                                      @RequestParam String commandName) {
        return new DeleteSkillResponse(skillService.deleteSkill(workingDirectory, commandName));
    }

    public record SkillFileRequest(String workingDirectory, String commandName, String content) {
    }

    public record DeleteSkillResponse(boolean deleted) {
    }
}
