package com.amazonaws.samples;


import com.amazonaws.*;
import com.amazonaws.auth.*;
import com.amazonaws.http.*;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;

import com.jayway.jsonpath.Configuration;

import com.jayway.jsonpath.JsonPath;

import lombok.NonNull;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import net.minidev.json.JSONArray;
import org.apache.commons.io.IOUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


public class AppFlowDataHandler implements RequestHandler<S3EventNotification, String> {


    private final AmazonS3 s3Client = AmazonS3ClientBuilder.standard().build();
    public final String healthLakeEndpoint = System.getenv("HL_ENDPOINT");
    public final String AWS_REGION = System.getenv("AWS_REGION");
    public final String accesKey = System.getenv("AWS_ACCESS_KEY_ID");
    public final String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
    public final String sessionToken = System.getenv("AWS_SESSION_TOKEN");

    public String handleRequest(@NonNull Map<String, Object> event, @NonNull final Context context) {
        LambdaLogger logger = context.getLogger();

        String bucketName = event.getOrDefault("Bucket", null);
        String key = event.getOrDefault("Key", null);


        logger.log("The bucket name : " + bucketName);
        logger.log("The object key :" + key);

        String appFlowData = s3Client.getObjectAsString(bucketName, key);

        logger.log("The appflow data :" + appFlowData);
        try {
            HashMap<String, String> fhirResources = this.buildFHIRResources(appFlowData, context);
            createPatientInHealthLake(fhirResources.get("PATIENT_RESOURCE"), context);

            //dose 1
            createImmunizationInHealthLake(fhirResources.get("IMM1_RESOURCE"), context);

            //dose 2
            createImmunizationInHealthLake(fhirResources.get("IMM2_RESOURCE"), context);
        } catch (ParseException parseException){
            logger.log(parseException.getMessage());
        }

        return appFlowData;

    }



    private HashMap<String,String> buildFHIRResources(String appFlowData, Context context)throws ParseException{
        LambdaLogger logger = context.getLogger();

        Object document = Configuration.builder().build().jsonProvider().parse(appFlowData);
        //DocumentContext context = JsonPath.parse(appFlowData);

        JSONArray nameArray = JsonPath.read(document, "$..name");
        String name = nameArray.get(0).toString();

        String firstName = name.split(" ")[0];

        String lastName = name.split(" ")[1];

        JSONArray vaccineDate1Array = JsonPath.read(document, "$..vaccine_date1__c");
        String immunizationDate1= convertToFHIRDate(vaccineDate1Array.get(0).toString());

        JSONArray vaccineDate2Array = JsonPath.read(document, "$..vaccine_date2__c");
        String immunizationDate2 = convertToFHIRDate(vaccineDate2Array.get(0).toString());

        JSONArray patientIdArray = JsonPath.read(document, "$..patientid__c");
        String patientId = patientIdArray.get(0).toString();

        logger.log(name + "-" + immunizationDate1 + "-" + immunizationDate2 + "-" + patientId);

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");

        ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());

        ve.init();

        //get patient template
        Template patTemplate = ve.getTemplate("patient.template");

        VelocityContext patVelocityContext = new VelocityContext();

        patVelocityContext.put("patientid",patientId);
        patVelocityContext.put("lastname", lastName);
        patVelocityContext.put("firstname", firstName);

        HashMap<String,String> resourceMap = new HashMap<>();

        //context.put("onsetDateTime",activityDatetime!=null?activityDatetime:"");
        /* now render the template into a StringWriter */
        StringWriter patStringWriter = new StringWriter();
        patTemplate.merge(patVelocityContext, patStringWriter);
        String patientResource = patStringWriter.toString();
        resourceMap.put("PATIENT_RESOURCE",patientResource);

        logger.log("Patient :"+patientResource);

        //get immunization template
        Template immTemplate = ve.getTemplate("immunization.template");

        VelocityContext imm1VelocityContext = new VelocityContext();

        imm1VelocityContext.put("patientid",patientId);
        imm1VelocityContext.put("immunizationdate", immunizationDate1);
        StringWriter imm1StringWriter = new StringWriter();
        immTemplate.merge(imm1VelocityContext, imm1StringWriter);
        String imm1Resource = imm1StringWriter.toString();
        resourceMap.put("IMM1_RESOURCE",imm1Resource);
        logger.log("Imm 1 resource :"+imm1Resource);

        VelocityContext imm2VelocityContext = new VelocityContext();
        imm2VelocityContext.put("patientid",patientId);
        imm2VelocityContext.put("immunizationdate", immunizationDate2);
        StringWriter imm2StringWriter = new StringWriter();
        immTemplate.merge(imm2VelocityContext, imm2StringWriter);
        String imm2Resource = imm2StringWriter.toString();
        resourceMap.put("IMM2_RESOURCE",imm2Resource);
        logger.log("Imm 2 resource :"+imm2Resource);

        //resourceMap.put("PATIENT_ID",patientId);

        return resourceMap;
    }

    public String convertToFHIRDate(String immunizationDate)throws ParseException {
        SimpleDateFormat sourceFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = sourceFormat.parse(immunizationDate);
        SimpleDateFormat fhirFormat = new SimpleDateFormat("yyyy-MM-dd");
        return fhirFormat.format(date);
    }

    public static void main(String []args)throws Exception{
        AppFlowDataHandler appFlowDataHandler = new AppFlowDataHandler();
        System.out.println(appFlowDataHandler.convertToFHIRDate("2020-11-11 10:15:27"));
    }

    private void createPatientInHealthLake(String patientResource, Context context) {
        LambdaLogger logger = context.getLogger();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(patientResource.getBytes(StandardCharsets.UTF_8));

        Request<Void> request = new DefaultRequest<>("healthlake");
        request.setHttpMethod(HttpMethodName.POST);
        request.setEndpoint(URI.create(String.format("%s%s", healthLakeEndpoint, "Patient")));
        request.setContent(byteArrayInputStream);

        AWS4Signer signer = new AWS4Signer();
        signer.setRegionName(AWS_REGION);
        signer.setServiceName("healthlake");


        signer.sign(request, new AWSSessionCredentials() {
            @Override
            public String getSessionToken() {
                return sessionToken;
            }

            @Override
            public String getAWSAccessKeyId() {
                return accesKey;
            }

            @Override
            public String getAWSSecretKey() {
                return secretKey;
            }
        });

        //Execute it and get the response...

        Response<String> rsp = new AmazonHttpClient(new ClientConfiguration())
                .requestExecutionBuilder()
                .executionContext(new ExecutionContext(true))
                .request(request)
                .execute(new HttpResponseHandler<String>() {
                    @Override
                    public String handle(HttpResponse response) throws Exception {

                        byte[] respContent = new byte[]{};
                        InputStream inputStream = response.getContent();
                        String result = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                        //String respBody = new String(respContent);
                        return result;
                    }

                    @Override
                    public boolean needsConnectionLeftOpen() {
                        return false;
                    }
                });
        String awsResponse = rsp.getAwsResponse();
        logger.log("The response " + awsResponse);

    }

    private void createImmunizationInHealthLake(String immunizationResource, Context context) {
        LambdaLogger logger = context.getLogger();


        //logger.log("Access key  : "+accesKey);

        //logger.log("Secret  : "+secretKey);

        logger.log("Resource body : "+immunizationResource);

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(immunizationResource.getBytes(StandardCharsets.UTF_8));

        Request<Void> request = new DefaultRequest<>("healthlake");
        request.setHttpMethod(HttpMethodName.POST);
        request.setEndpoint(URI.create(String.format("%s%s", healthLakeEndpoint, "Immunization")));
        request.setContent(byteArrayInputStream);

        AWS4Signer signer = new AWS4Signer();
        signer.setRegionName(AWS_REGION);
        signer.setServiceName("healthlake");


        signer.sign(request, new AWSSessionCredentials() {
            @Override
            public String getSessionToken() {
                return sessionToken;
            }

            @Override
            public String getAWSAccessKeyId() {
                return accesKey;
            }

            @Override
            public String getAWSSecretKey() {
                return secretKey;
            }
        });

        //Execute it and get the response...

        Response<String> rsp = new AmazonHttpClient(new ClientConfiguration())
                .requestExecutionBuilder()
                .executionContext(new ExecutionContext(true))
                .request(request)
                .execute(new HttpResponseHandler<String>() {
                    @Override
                    public String handle(HttpResponse response) throws Exception {


                        InputStream inputStream = response.getContent();
                        String result = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
                        //String respBody = new String(respContent);
                        return result;

                    }

                    @Override
                    public boolean needsConnectionLeftOpen() {
                        return false;
                    }
                });
        String awsResponse = rsp.getAwsResponse();
        logger.log("The response " + awsResponse);

    }

}
