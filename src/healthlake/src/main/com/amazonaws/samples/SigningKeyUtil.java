package com.amazonaws.samples;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.DefaultRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.auth.*;
import com.amazonaws.http.*;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.InputStream;
import java.net.URI;


import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

public class SigningKeyUtil {
    public static void main(String[]args)throws  Exception{
        //postUsinghttpClient();
        String secretKey = "6cL7wwWDE2RulB+YFcfiNcwFB9T9BI9XqJgb8dkX";
        String accesKey = "AKIAZ5RNMKBAZZQ26JPR";

        //AWSCredentials awsCredentials = (new DefaultAWSCredentialsProviderChain()).getCredentials();
        //String secretKey = awsCredentials.getAWSSecretKey();
        //String accesKey = awsCredentials.getAWSAccessKeyId();


        Request<Void> request = new DefaultRequest<>("healthlake"); //Request to ElasticSearch
        request.setHttpMethod(HttpMethodName.GET);
        request.setEndpoint(URI.create("https://healthlake.us-west-2.amazonaws.com/datastore/0ac66a76e6d2b88ec98773be5d09e4ee/r4/CapabilityStatement"));


        AWS4Signer signer = new AWS4Signer();
        signer.setRegionName("us-west-2");
        signer.setServiceName("healthlake");
        signer.sign(request, new AWSCredentials() {
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

        Response < String > rsp = new AmazonHttpClient(new ClientConfiguration())
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

        System.out.println("The response "+rsp.getAwsResponse());


        //byte[] key = getSignatureKey(secretKey,"20210912","us-west-2","healthlake");

        //System.out.println("The SIGV4 Key :"+HmacSHA256("ddsdasds ddsdsamithundsa",key));




    }


    static byte[] HmacSHA256(String data, byte[] key) throws Exception {

        String algorithm="HmacSHA256";
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return mac.doFinal(data.getBytes("UTF-8"));
    }

    static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) throws Exception {
        byte[] kSecret = ("AWS4" + key).getBytes("UTF-8");
        byte[] kDate = HmacSHA256(dateStamp, kSecret);
        byte[] kRegion = HmacSHA256(regionName, kDate);
        byte[] kService = HmacSHA256(serviceName, kRegion);
        byte[] kSigning = HmacSHA256("aws4_request", kService);
        return kSigning;
    }

}
