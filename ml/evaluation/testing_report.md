\# CRM AI Lead Management Assistant



\# Functional Testing Report



\---



\## Objective



The objective of functional testing is to verify whether the fine-tuned CRM AI model generates relevant responses for different lead scenarios.



\---



\# Test Environment



Model:

Meta Llama 3.1 8B Instruct



Fine-Tuning:

QLoRA + LoRA



GPU:

NVIDIA RTX A4000



Dataset:

50 CRM Training Examples



Inference:

Interactive CLI



\---



\# Test Case 1



\## Input



Company: ABC Technologies



Industry: Healthcare



Employees: 250



Budget: 15 Lakhs



Decision Maker: Yes



Interested Product: CRM Software



\### Expected



High-quality lead



Positive conversion probability



\### Actual Output



"This customer shows positive engagement because they have expressed interest in the product and are willing to make a decision."



\### Result



PASS



\---



\# Test Case 2



\## Input



Company: BrightAI



Industry: AI



Employees: 18



Budget: 2 Lakhs



Decision Maker: Founder



Interested Product: CRM Basic



\### Expected



Medium-quality lead



\### Actual Output



"This customer shows positive engagement because they have shown interest in the product and are likely to convert."



\### Result



PARTIAL PASS



\---



\# Test Case 3



\## Input



Company: XYZ Traders



Industry: Retail



Employees: 8



Budget: 30,000



Decision Maker: No



Interested Product: Enterprise CRM



\### Expected



Low-quality lead



\### Actual Output



"This customer shows positive engagement because they have shown interest in the product and are willing to invest time in it."



\### Result



FAIL



Reason:



The model ignored the low budget and lack of decision-maker.



\---



\# Test Case 4



\## Input



Company: Demo Company



Industry: Manufacturing



Employees: 400



Budget: Unknown



Decision Maker: No



Interested Product: None



\### Expected



Poor-quality lead



\### Actual Output



"This lead is unlikely because the customer shows low interest in the product."



\### Result



PASS



\---



\# Test Case 5



\## Input



Company: Infosys



Industry: IT



Employees: 300000



Budget: 3 Crores



Decision Maker: CIO



Interested Product: CRM Enterprise



\### Expected



High-priority enterprise lead



\### Actual Output



Positive engagement response



\### Result



PARTIAL PASS



Reason:



The model recognized positive engagement but did not identify enterprise priority.



\---



\# Test Case 6



\## Input



Company: Unknown



\### Expected



Ask for additional information.



\### Actual Output



Positive engagement response.



\### Result



FAIL



Reason:



The model should request missing information instead of assuming a positive lead.



\---



\# Overall Results



| Test Case | Status |

|------------|--------|

| Test Case 1 | PASS |

| Test Case 2 | PARTIAL PASS |

| Test Case 3 | FAIL |

| Test Case 4 | PASS |

| Test Case 5 | PARTIAL PASS |

| Test Case 6 | FAIL |



\---



\# Summary



Total Test Cases:

6



PASS:

2



PARTIAL PASS:

2



FAIL:

2



Estimated Accuracy:

Approximately 60%



\---



\# Observations



The fine-tuned model successfully generates CRM-oriented responses.



The current dataset of 50 examples is insufficient for robust lead qualification.



The model tends to generate generic positive responses and struggles with:



\- Low-budget leads

\- Missing information

\- Enterprise prioritization

\- Structured lead scoring



\---



\# Recommendations



Increase the dataset to at least 1000 CRM examples.



Include:



\- Positive leads

\- Negative leads

\- Hot/Warm/Cold classifications

\- Lead scores (0–100)

\- Follow-up recommendations

\- Missing-information scenarios

\- Industry-specific cases

