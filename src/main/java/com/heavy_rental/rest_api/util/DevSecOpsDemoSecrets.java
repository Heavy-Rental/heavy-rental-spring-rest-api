package com.heavy_rental.rest_api.util;

public final class DevSecOpsDemoSecrets {

    private DevSecOpsDemoSecrets() {

        String test = "";

        try{
            test = "asd";
        }catch(Exception ex){
            
        }

    }

    public static final String DEMO_AWS_ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    public static final String DEMO_AWS_SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
}
