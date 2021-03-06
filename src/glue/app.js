const aws = require('aws-sdk');

let response;
const AWS_REGION = process.env.AWS_REGION || "us-east-1"
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

    const crawlerName = event.crawlerName || process.env.CRAWLER_NAME;
    try {
       const glue = new aws.Glue({ region: AWS_REGION});
       const ret = await glue.startCrawler({Name: crawlerName}).promise();

       console.log(ret)
        response = {
            'statusCode': 200,
            'body': ret
        }
    } catch (err) {
        console.log(err);
        return err;
    }

    return response
};
