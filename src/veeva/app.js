const aws = require('aws-sdk');
const s3 = new aws.S3({ apiVersion: '2006-03-01' });
const athena = require("athena-express");

const awsCredentials = {
    region: "us-east-1"
};
aws.config.update(awsCredentials);


const athenaExpressConfig = { aws: aws, ignoreEmpty: false }; //configuring athena-express with aws sdk object
const athenaExpress = new athena(athenaExpressConfig);

let response;
const tableName = process.env.GlueCatalogTable;
const dbName = process.env.GlueCatalogDatabase;
const tableKey = process.env.GlueTableKey;

/**
 *
 * Event doc: https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html#api-gateway-simple-proxy-for-lambda-input-format
 * @param {Object} event - API Gateway Lambda Proxy Input Format
 *
 * Context doc: https://docs.aws.amazon.com/lambda/latest/dg/nodejs-prog-model-context.html 
 * @param {Object} context
 *
 * Return doc: https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html
 * @returns {Object} object - API Gateway Lambda Proxy Output Format
 * 
 */
exports.lambdaHandler = async (event, context) => {
    try {
        // const ret = await axios(url);
        const s3Details = event.detail.requestParameters;
        const params = {
            Bucket: s3Details.bucketName,
            Key: s3Details.key,
        }; 
        try {
            const file = await s3.getObject(params).promise()
            const veevaMetadata = JSON.parse(file.Body.toString('utf-8'));
            var PatientData = {};
            veevaMetadata.data.forEach( (v)  => {
                if (v != null && v.reference_source__c) {
                    var toJson = JSON.parse(v.reference_source__c.toString('utf-8'));

                    PatientData[toJson["PatientID"]] = {
                            "veeva": toJson
                    }
                }
            });

            var sql = "SELECT * FROM \""+dbName+"\".\""+tableName+"\" WHERE "+tableKey+" IN ("+ "'"+Object.keys(PatientData).join("','")+"'" +")";
            var AthenaResult = await athenaExpress.query(sql);

            AthenaResult.Items.forEach( (v)  => {
                if (v != null && v.patientid__c) {
                    if (PatientData[v.patientid__c] && PatientData[v.patientid__c]["veeva"]) {
                        v["is_fully_vacinated__c"] = (PatientData[v.patientid__c]["veeva"]["Vaccination"].length>=2)
                        v["first_vaccine_applied__c"] = (PatientData[v.patientid__c]["veeva"]["Vaccination"].length>=1)
                        v["second_vaccine_applied__c"] = (PatientData[v.patientid__c]["veeva"]["Vaccination"].length>=2)

                        PatientData[v.patientid__c]["veeva"]["Vaccination"].forEach( (k, i) => {
                            v["vaccine_date"+(i+1)+"__c"] = k.date;
                        }) 
                    }
                    PatientData[v.patientid__c] = v
                }
            });
            
            let dstKey = "aggregated/"+event.id+".json";
            console.log(JSON.stringify(PatientData));

            const putObjectParams = {
                Bucket: s3Details.bucketName,
                Key: dstKey,
                Body: JSON.stringify(PatientData),
                ContentType: "application/json",
                ACL: "bucket-owner-full-control"
               };

            await s3.putObject(putObjectParams).promise()
            
            response = {
                Bucket: s3Details.bucketName,
                Key: dstKey,
            };
            
        } catch (err) {
            console.log(err);
            const message = `Error getting object ${key} from bucket ${bucket}. Make sure they exist and your bucket is in the same region as this function.`;
            console.log(message);
            throw new Error(message);
        }        

    } catch (err) {
        console.log(err);
        return err;
    }

    return response
};

