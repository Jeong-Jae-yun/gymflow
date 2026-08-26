package com.gymflow;

import org.springframework.boot.SpringApplication;

public class TestGymFlowApplication {

    public static void main(String[] args) {
        SpringApplication.from(GymFlowApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
