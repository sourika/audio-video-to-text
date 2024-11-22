package com.example.filetoarticleconverter.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.UUID;

@Controller
public class WebController {

    @GetMapping("/")
    public String showVideoConverterForm(Model model) {
        String username = "user_" + UUID.randomUUID();
        model.addAttribute("username", username);
        return "videoConverter";
    }
}

