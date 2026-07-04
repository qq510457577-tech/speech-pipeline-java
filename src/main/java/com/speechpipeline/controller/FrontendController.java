package com.speechpipeline.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;

@Controller
public class FrontendController {

    @GetMapping({"/speech", "/speech/", "/speech/index.html"})
    public ResponseEntity<String> serveSpeechIndex() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/index.html");
        byte[] content = resource.getInputStream().readAllBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new String(content));
    }

    @GetMapping("/speech/{path:[^\\.]+}")
    public ResponseEntity<String> serveSpeechPage(@PathVariable String path) throws IOException {
        ClassPathResource resource = new ClassPathResource("static/" + path);
        if (resource.exists()) {
            byte[] content = resource.getInputStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(new String(content));
        }
        return ResponseEntity.notFound().build();
    }
}
