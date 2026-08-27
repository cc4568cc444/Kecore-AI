package com.cc.springai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class VueFrontendController {

    @GetMapping({"/", "/index.html"})
    public String root() {
        return "redirect:/vue/";
    }

    @GetMapping({"/vue", "/vue/"})
    public String vueIndex() {
        return "forward:/vue/index.html";
    }
}
